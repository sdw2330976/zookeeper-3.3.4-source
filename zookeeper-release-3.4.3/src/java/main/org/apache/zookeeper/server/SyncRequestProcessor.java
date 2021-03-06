/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server;

import java.io.Flushable;
import java.io.IOException;
import java.util.LinkedList;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This RequestProcessor logs requests to disk. It batches the requests to do
 * the io efficiently. The request is not passed to the next RequestProcessor
 * until its log has been synced to disk.
 * 
 * 事务日志记录处理器  该处理器主要用来将事务请求记录到事务日志文件中去,同时会触发ZooKeeper进行数据快照。
 */
public class SyncRequestProcessor extends Thread implements RequestProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(SyncRequestProcessor.class);
    private final ZooKeeperServer zks;
    private final LinkedBlockingQueue<Request> queuedRequests =
        new LinkedBlockingQueue<Request>();
    private final RequestProcessor nextProcessor;

    private Thread snapInProcess = null;
    volatile private boolean running;

    /**
     * Transactions that have been written and are waiting to be flushed to
     * disk. Basically this is the list of SyncItems whose callbacks will be
     * invoked after flush returns successfully.
     */
    private final LinkedList<Request> toFlush = new LinkedList<Request>();//已经被写的事务等待flush同步到磁盘的事务请求队列
    private final Random r = new Random(System.nanoTime());
    /**
     * The number of log entries to log before starting a snapshot
     */
    private static int snapCount = ZooKeeperServer.getSnapCount();

    private final Request requestOfDeath = Request.requestOfDeath;

    public SyncRequestProcessor(ZooKeeperServer zks,
            RequestProcessor nextProcessor)
    {
        super("SyncThread:" + zks.getServerId());
        this.zks = zks;
        this.nextProcessor = nextProcessor;
        running = true;
    }

    /**
     * used by tests to check for changing
     * snapcounts
     * @param count
     */
    public static void setSnapCount(int count) {
        snapCount = count;
    }

    /**
     * used by tests to get the snapcount
     * @return the snapcount
     */
    public static int getSnapCount() {
        return snapCount;
    }

    @Override
    public void run() {
        try {
            int logCount = 0;

            // we do this in an attempt to ensure that not all of the servers
            // in the ensemble take a snapshot at the same time
            int randRoll = r.nextInt(snapCount/2);
            while (true) {
                Request si = null;
                //flush队列如果为空 阻塞等待 代表之前的请求都被处理了
                if (toFlush.isEmpty()) {
                    si = queuedRequests.take();
                } else {
                	//如果不为空，就是说还有请求等待处理，先非阻塞拿一下，如果系统压力小，正好没有请求进来，则处理之前积压的请求
                	//如果系统压力大，则可能需要flush满1000个才会继续处理  
                    si = queuedRequests.poll();
                    if (si == null) {//任务queue空闲，处理积压的待flush请求 
                        flush(toFlush); //压力小的情况 直接处理
                        continue;
                    }
                }
                if (si == requestOfDeath) {
                    break;
                }
                if (si != null) {
                    // track the number of records written to the log
                	//将Request append到log输出流，先序列化再append，注意此时request还没flush到磁盘，还在内存呢  
                    if (zks.getZKDatabase().append(si)) {
                        logCount++;//成功计数器  
                        //如果成功append的request累计数量大于某个值，则执行flush log的操作  
                        //并启动一个线程异步将内存里的Database和session状态写入到snapshot文件，相当于一个checkpoint  
                        //snapCount默认是100000  
                        if (logCount > (snapCount / 2 + randRoll)) {//每进行snapCount次事务日志输出后会触发一次快照(snapshot) 此时ZooKeeper会生成一个snapshot.*同时新建一个事务日志log.*文件 默认值为 100000
                            randRoll = r.nextInt(snapCount/2);
                            // roll the log
                            //将内存中的log flush到磁盘  logStream.flush
                            zks.getZKDatabase().rollLog();
                            // take a snapshot
                            //启动线程异步将内存中的database和sessions状态写入snapshot文件中  
                            if (snapInProcess != null && snapInProcess.isAlive()) {
                                LOG.warn("Too busy to snap, skipping");
                            } else {
                                snapInProcess = new Thread("Snapshot Thread") {
                                        public void run() {
                                            try {
                                                zks.takeSnapshot();//创建快照文件
                                            } catch(Exception e) {
                                                LOG.warn("Unexpected exception", e);
                                            }
                                        }
                                    };
                                snapInProcess.start();
                            }
                            logCount = 0;
                        }
                        //如果是写请求，而且flush队列为空，执行往下执行   
                    } else if (toFlush.isEmpty()) {
                        // optimization for read heavy workloads
                        // iff this is a read, and there are no pending
                        // flushes (writes), then just pass this to the next
                        // processor
                        nextProcessor.processRequest(si);
                        if (nextProcessor instanceof Flushable) {
                            ((Flushable)nextProcessor).flush();
                        }
                        continue;
                    }
                  //写请求前面append到log输出流后，在这里加入到flush队列，后续批量处理  
                    toFlush.add(si);
                    //如果系统压力大，可能需要到1000个request才会flush，flush之后可以被后续processor处理  
                    if (toFlush.size() > 1000) {
                        flush(toFlush);
                    }
                }
            }
        } catch (Throwable t) {
            LOG.error("Severe unrecoverable error, exiting", t);
            running = false;
            System.exit(11);
        }
        LOG.info("SyncRequestProcessor exited!");
    }

    private void flush(LinkedList<Request> toFlush) throws IOException {
        if (toFlush.isEmpty())
            return;
        //将之前的append log flush到磁盘，并顺便关闭旧的log文件句柄  
        zks.getZKDatabase().commit();
      //log flush完后，开始处理flush队列里的Request  
        while (!toFlush.isEmpty()) {
            Request i = toFlush.remove();
          //执行后面的processor  
            nextProcessor.processRequest(i);
        }
        if (nextProcessor instanceof Flushable) {
            ((Flushable)nextProcessor).flush();
        }
    }

    public void shutdown() {
        LOG.info("Shutting down");
        queuedRequests.add(requestOfDeath);
        try {
            if(running){
                this.join();
            }
        } catch(InterruptedException e) {
            LOG.warn("Interrupted while wating for " + this + " to finish");
        }
        nextProcessor.shutdown();
    }

    public void processRequest(Request request) {
        // request.addRQRec(">sync");
        queuedRequests.add(request);
    }

}
