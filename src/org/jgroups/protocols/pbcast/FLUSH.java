package org.jgroups.protocols.pbcast;

import org.jgroups.*;
import org.jgroups.annotations.*;
import org.jgroups.conf.AttributeType;
import org.jgroups.stack.Protocol;
import org.jgroups.util.*;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Flush, as it name implies, forces group members to flush their pending messages while blocking
 * them to send any additional messages. The process of flushing acquiesces the group so that state
 * transfer or a join can be done. It is also called stop-the-world model as nobody will be able to
 * send messages while a flush is in process.
 * 
 * <p>
 * Flush is needed for:
 * <p>
 * (1) State transfer. When a member requests state transfer, the coordinator tells everyone to stop
 * sending messages and waits for everyone's ack. Then it asks the application for its state and
 * ships it back to the requester. After the requester has received and set the state successfully,
 * the coordinator tells everyone to resume sending messages.
 * <p>
 * (2) View changes (e.g.a join). Before installing a new view V2, flushing would ensure that all
 * messages *sent* in the current view V1 are indeed *delivered* in V1, rather than in V2 (in all
 * non-faulty members). This is essentially Virtual Synchrony.
 * 
 * 
 * 
 * @author Vladimir Blagojevic
 * @since 2.4
 */
@MBean(description = "Flushes the cluster")
public class FLUSH extends Protocol {

    private static final FlushStartResult SUCCESS_START_FLUSH = new FlushStartResult(Boolean.TRUE,null);

    /*
     * ------------------------------------------ Properties------------------------------------------
     */
    @Property(description = "Max time to keep channel blocked in flush. Default is 8000 msec",type=AttributeType.TIME)
    protected long timeout = 8000;

    @Property(description = "Timeout (per atttempt) to quiet the cluster during the first flush phase. " +
      "Default is 2000 msec",type=AttributeType.TIME)
    protected long start_flush_timeout = 2000;
    
    @Property(description = "Timeout to wait for UNBLOCK after STOP_FLUSH is issued. Default is 2000 msec",type=AttributeType.TIME)
    protected long end_flush_timeout = 2000;

    @Property(description = "Retry timeout after an unsuccessful attempt to quiet the cluster (first flush phase)." +
      "Default is 3000 msec", type=AttributeType.TIME)
    protected long retry_timeout = 2000;

    @Property(description = "Reconciliation phase toggle. Default is true")
    protected boolean enable_reconciliation = true;

    @Property(description="When set, FLUSH is bypassed, same effect as if FLUSH wasn't in the config at all")
    protected boolean bypass=false;

    /*
     * --------------------------------------------- JMX ----------------------------------------------
     */

    private long startFlushTime;

    private long totalTimeInFlush;

    private int numberOfFlushes;

    private double averageFlushDuration;

    /*
     * --------------------------------------------- Fields------------------------------------------------------
     */

    @GuardedBy("sharedLock")
    private View currentView=new View(new ViewId(), new ArrayList<>());

    /**
     * Group member that requested FLUSH. For view installations flush coordinator is the group
     * coordinator For state transfer flush coordinator is the state requesting member
     */
    @GuardedBy("sharedLock")
    private Address flushCoordinator;

    @GuardedBy("sharedLock")
    private final List<Address> flushMembers=new ArrayList<>();

    private final AtomicInteger viewCounter = new AtomicInteger(0);

    @GuardedBy("sharedLock")
    private final Map<Address, Digest> flushCompletedMap=new HashMap<>();

    @GuardedBy("sharedLock")
    private final List<Address> flushNotCompletedMap=new ArrayList<>();

    @GuardedBy("sharedLock")
    private final Set<Address> suspected=new TreeSet<>();

    @GuardedBy("sharedLock")
    private final List<Address> reconcileOks=new ArrayList<>();

    private final Object sharedLock = new Object();

    private final ReentrantLock blockMutex = new ReentrantLock();
    
    private final Condition notBlockedDown = blockMutex.newCondition();

    /**
     * Indicates if FLUSH.down() is currently blocking threads Condition predicate associated with
     * blockMutex
     */
    @ManagedAttribute(description="Is message sending currently blocked")
    @GuardedBy("blockMutex")
    private volatile boolean isBlockingFlushDown = true;

    @GuardedBy("sharedLock")
    private boolean flushCompleted = false;

    private final Promise<FlushStartResult> flush_promise = new Promise<>();

    private final Promise<Boolean> flush_unblock_promise = new Promise<>();

    private final AtomicBoolean flushInProgress = new AtomicBoolean(false);

    private final AtomicBoolean sentBlock = new AtomicBoolean(false);

    private final AtomicBoolean sentUnblock = new AtomicBoolean(false);


    public long getStartFlushTimeout() {
        return start_flush_timeout;
    }

    public FLUSH setStartFlushTimeout(long start_flush_timeout) {
        this.start_flush_timeout = start_flush_timeout;
        return this;
    }

    public long getRetryTimeout() {
        return retry_timeout;
    }

    public FLUSH setRetryTimeout(long retry_timeout) {
        this.retry_timeout = retry_timeout;
        return this;
    }

    public void start() throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put("flush_supported", Boolean.TRUE);
        up_prot.up(new Event(Event.CONFIG, map));
        down_prot.down(new Event(Event.CONFIG, map));

        viewCounter.set(0);
        blockMutex.lock();
        try {
            isBlockingFlushDown = true;
        } finally {
            blockMutex.unlock();
        }
    }

    public void stop() {
        synchronized (sharedLock) {
            currentView = new View(new ViewId(), new ArrayList<>());
            flushCompletedMap.clear();
            flushNotCompletedMap.clear();
            flushMembers.clear();
            suspected.clear();
            flushCoordinator = null;
        }
    }

    /* -------------------JMX attributes and operations --------------------- */

    @ManagedAttribute(type=AttributeType.TIME)
    public double getAverageFlushDuration() {
        return averageFlushDuration;
    }

    @ManagedAttribute(type=AttributeType.TIME)
    public long getTotalTimeInFlush() {
        return totalTimeInFlush;
    }

    @ManagedAttribute
    public int getNumberOfFlushes() {
        return numberOfFlushes;
    }

    @ManagedOperation(description="Sets the bypass flag")
    public boolean setBypass(boolean flag) {
        boolean ret=bypass;
        bypass=flag;
        return ret;
    }

    @ManagedOperation(description = "Request cluster flush")
    public void startFlush() {
        startFlush(new Event(Event.SUSPEND));
    }

    private void startFlush(Event evt) {
        List<Address> flushParticipants =evt.getArg();
        startFlush(flushParticipants);
    }

    private void startFlush(List<Address> flushParticipants) {
        if (!flushInProgress.get()) {
            flush_promise.reset();
            synchronized(sharedLock) {
                if(flushParticipants == null)
                    flushParticipants=new ArrayList<>(currentView.getMembers());
            }
            onSuspend(flushParticipants);
            try {
                FlushStartResult r = flush_promise.getResultWithTimeout(start_flush_timeout);
                if(r.failed())
                    throw new RuntimeException(r.getFailureCause());
            } catch (TimeoutException e) {
                Set<Address> missingMembers;
                synchronized(sharedLock) {
                    missingMembers=new HashSet<>(flushMembers);
                    missingMembers.removeAll(flushCompletedMap.keySet());
                }

                rejectFlush(flushParticipants, currentViewId());
                throw new RuntimeException(local_addr
                            + " timed out waiting for flush responses from "
                            + missingMembers
                            + " after "
                            + start_flush_timeout
                            + " ms. Rejected flush to participants "
                            + flushParticipants, e);
            }
        }
        else {
            throw new RuntimeException("Flush attempt is in progress");
        }        
    }

    @ManagedOperation(description = "Request end of flush in a cluster")
    public void stopFlush() {
        down(new Event(Event.RESUME));
    }

    /*
     * ------------------- end JMX attributes and operations ---------------------
     */

    public Object down(Event evt) {
        if(!bypass){
           switch (evt.getType()) {
               case Event.CONNECT:
               case Event.CONNECT_USE_FLUSH:
                   return handleConnect(evt,true);

               case Event.CONNECT_WITH_STATE_TRANSFER:
               case Event.CONNECT_WITH_STATE_TRANSFER_USE_FLUSH:
                   return handleConnect(evt, false);

               case Event.SUSPEND:
                   startFlush(evt);
                   return null;

               // only for testing, see FLUSH#testFlushWithCrashedFlushCoordinator
               case Event.SUSPEND_BUT_FAIL:
                   if (!flushInProgress.get()) {
                       flush_promise.reset();
                       ArrayList<Address> flushParticipants = null;
                       synchronized (sharedLock) {
                           flushParticipants = new ArrayList<>(currentView.getMembers());
                       }
                       onSuspend(flushParticipants);
                   }
                   break;

               case Event.RESUME:
                   onResume(evt);
                   return null;
           }
        }
        return down_prot.down(evt);
    }

    public Object down(Message msg) {
        if(!bypass) {
            Address dest = msg.getDest();
            if (dest == null) { // mcasts
                FlushHeader fh =msg.getHeader(this.id);
                if (fh != null && fh.type == FlushHeader.FLUSH_BYPASS) {
                    return down_prot.down(msg);
                } else {
                    blockMessageDuringFlush();
                }
            } else {
                // unicasts are irrelevant in virtual synchrony, let them through
                return down_prot.down(msg);
            }
        }
        return down_prot.down(msg);
    }

    private Object handleConnect(Event evt, boolean waitForUnblock) {
        if (sentBlock.compareAndSet(false, true)) {
            sendBlockUpToChannel();
        }

        Object result = down_prot.down(evt);
        if (result instanceof Throwable) {
            // set the var back to its original state if we cannot
            // connect successfully
            sentBlock.set(false);
        }
        if(waitForUnblock)
            waitForUnblock();
        return result;
    }

    private void blockMessageDuringFlush() {
        boolean shouldSuspendByItself = false;
        blockMutex.lock();
        try {
            while (isBlockingFlushDown) {
                if (log.isDebugEnabled())
                    log.debug(local_addr + ": blocking for " + (timeout <= 0 ? "ever" : timeout + "ms"));
                if(timeout <= 0) {
                   notBlockedDown.await();
                }
                else { 
                   shouldSuspendByItself = !notBlockedDown.await(timeout, TimeUnit.MILLISECONDS);
                }
                
                if (shouldSuspendByItself) {
                   isBlockingFlushDown = false;      
                   log.warn(local_addr + ": unblocking after " + timeout + "ms");
                   flush_promise.setResult(new FlushStartResult(Boolean.TRUE,null));
                   notBlockedDown.signalAll();
               }
            }                        
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            blockMutex.unlock();
        }        
    }

    public Object up(Event evt) {
        if(!bypass) {
           switch (evt.getType()) {
               case Event.VIEW_CHANGE:
                   // JGRP-618: FLUSH coordinator transfer reorders block/unblock/view events in applications (TCP stack only)
                   up_prot.up(evt);
                   View newView =evt.getArg();
                   boolean coordinatorLeft = onViewChange(newView);
                   boolean singletonMember = newView.size() == 1 && newView.containsMember(local_addr);
                   boolean isThisOurFirstView = viewCounter.addAndGet(1) == 1;
                   // if this is channel's first view and its the only member of the group - no flush
                   // was run but the channel application should still receive BLOCK,VIEW,UNBLOCK

                   // also if coordinator of flush left each member should run stopFlush individually.
                   if ((isThisOurFirstView && singletonMember) || coordinatorLeft)
                       onStopFlush();
                   return null;

               case Event.TMP_VIEW:
                   View tmpView =evt.getArg();
                   if (!tmpView.containsMember(local_addr))
                       onViewChange(tmpView);
                   break;

               case Event.SUSPECT:
                   Collection<Address> suspects=evt.arg() instanceof Address? Collections.singletonList(evt.arg()) : evt.arg();
                   onSuspect(suspects);
                   break;

               case Event.SUSPEND:
                   startFlush(evt);
                   return null;

               case Event.RESUME:
                   onResume(evt);
                   return null;
               case Event.UNBLOCK:
                   flush_unblock_promise.setResult(Boolean.TRUE);
                   break;
           }
        }
        return up_prot.up(evt);
    }

    public Object up(Message msg) {
        if(!bypass) {
            final FlushHeader fh =msg.getHeader(this.id);
            if (fh != null) {
                final Tuple<Collection<? extends Address>,Digest> tuple=readParticipantsAndDigest(msg.getArray(),
                                                                                                  msg.getOffset(),
                                                                                                  msg.getLength());
                switch (fh.type) {
                    case FlushHeader.FLUSH_BYPASS:
                        return up_prot.up(msg);
                    case FlushHeader.START_FLUSH:

                        Collection<? extends Address> fp = tuple.getVal1();
                        boolean amIParticipant = (fp != null && fp.contains(local_addr))
                          || msg.getSrc().equals(local_addr);
                        if (amIParticipant) {
                            handleStartFlush(msg, fh);
                        } else {
                            if (log.isDebugEnabled())
                                log.debug(local_addr + ": received START_FLUSH but I'm not flush participant, not responding");
                        }
                        break;
                    case FlushHeader.FLUSH_RECONCILE:
                        handleFlushReconcile(msg);
                        break;
                    case FlushHeader.FLUSH_RECONCILE_OK:
                        onFlushReconcileOK(msg);
                        break;
                    case FlushHeader.STOP_FLUSH:
                        onStopFlush();
                        break;
                    case FlushHeader.ABORT_FLUSH:
                        Collection<? extends Address> flushParticipants = tuple.getVal1();
                        boolean participant = flushParticipants != null && flushParticipants.contains(local_addr);
                        if (log.isDebugEnabled()) {
                            log.debug(local_addr + ": received ABORT_FLUSH from flush coordinator " + msg.getSrc()
                                        + ",  am I flush participant=" + participant);
                        }
                        if (participant)
                            resetForNextFlush();
                        break;
                    case FlushHeader.FLUSH_NOT_COMPLETED:
                        if (log.isDebugEnabled()) {
                            log.debug(local_addr + ": received FLUSH_NOT_COMPLETED from " + msg.getSrc());
                        }
                        boolean flushCollision = false;
                        synchronized (sharedLock) {
                            flushNotCompletedMap.add(msg.getSrc());
                            flushCollision = !flushCompletedMap.isEmpty();
                            if (flushCollision) {
                                flushNotCompletedMap.clear();
                                flushCompletedMap.clear();
                            }
                        }

                        if (log.isDebugEnabled())
                            log.debug(local_addr + ": received FLUSH_NOT_COMPLETED from " + msg.getSrc() +
                                        " collision=" + flushCollision);

                        // reject flush if we have at least one OK and at least one FAIL
                        if (flushCollision) {
                            Runnable r =() -> rejectFlush(tuple.getVal1(), fh.viewID);
                            new Thread(r).start();
                        }
                        // however, flush should fail/retry as soon as one FAIL is received
                        flush_promise.setResult(new FlushStartResult(Boolean.FALSE, new Exception("Flush failed for " + msg.getSrc())));
                        break;

                    case FlushHeader.FLUSH_COMPLETED:
                        if (isCurrentFlushMessage(fh))
                            onFlushCompleted(msg.getSrc(), msg, fh);
                        break;
                }
                return null; // do not pass FLUSH msg up
            } else {
                // http://jira.jboss.com/jira/browse/JGRP-575: for processing of application messages after we join,
                // lets wait for STOP_FLUSH to complete before we start allowing message up
                if (msg.getDest() != null)
                    return up_prot.up(msg); // allow unicasts to pass, virtual synchrony only applies to multicasts
            }
        }
        return up_prot.up(msg);
    }

    public void up(MessageBatch batch) {
        if(bypass) {
            up_prot.up(batch);
            return;
        }
        Iterator<Message> it=batch.iterator();
        while(it.hasNext()) {
            Message msg=it.next();
            if(msg.getHeader(id) != null) {
                it.remove();
                up(msg); // let the existing code handle this
            }
            else {
                if(msg.getDest() != null) { // skip unicast messages, process them right away
                    it.remove();
                    up_prot.up(msg);
                }
            }
        }

        if(!batch.isEmpty())
            up_prot.up(batch);
    }

    private void waitForUnblock() {
        try {
            flush_unblock_promise.getResultWithTimeout(end_flush_timeout);
        } catch (TimeoutException t) {
            if (log.isWarnEnabled())
                log.warn(local_addr + ": waiting for UNBLOCK timed out after " + end_flush_timeout + " ms");
        }
        finally {
            flush_unblock_promise.reset();
        }
    }

    private void onFlushReconcileOK(Message msg) {
        if (log.isDebugEnabled())
            log.debug(local_addr + ": received reconcile ok from " + msg.getSrc());

        synchronized (sharedLock) {
            reconcileOks.add(msg.getSrc());
            if (reconcileOks.size() >= flushMembers.size()) {
                flush_promise.setResult(SUCCESS_START_FLUSH);
                if (log.isDebugEnabled())
                    log.debug(local_addr + ": all FLUSH_RECONCILE_OK received");
            }
        }
    }

    private void handleFlushReconcile(Message msg) {
        Address requester = msg.getSrc();
        Tuple<Collection<? extends Address>,Digest> tuple=readParticipantsAndDigest(msg.getArray(),
                                                                                    msg.getOffset(),msg.getLength());
        Digest reconcileDigest = tuple.getVal2();

        if (log.isDebugEnabled())
            log.debug(local_addr + ": received FLUSH_RECONCILE, passing digest to NAKACK "
                    + reconcileDigest);

        // Let NAKACK reconcile missing messages
        down_prot.down(new Event(Event.REBROADCAST, reconcileDigest));

        if (log.isDebugEnabled())
            log.debug(local_addr + ": returned from FLUSH_RECONCILE, "
                    + " sending RECONCILE_OK to " + requester);

        Message reconcileOk = new EmptyMessage(requester).setFlag(Message.Flag.OOB)
          .putHeader(this.id,new FlushHeader(FlushHeader.FLUSH_RECONCILE_OK));
        down_prot.down(reconcileOk);
    }

    private void handleStartFlush(Message msg, FlushHeader fh) {
        Address flushRequester = msg.getSrc();

        boolean proceed = flushInProgress.compareAndSet(false, true);
        if (proceed) {
            synchronized (sharedLock) {
                flushCoordinator = flushRequester;
            }
            onStartFlush(flushRequester, msg, fh);
        } else {
            Tuple<Collection<? extends Address>,Digest> tuple=readParticipantsAndDigest(msg.getArray(),
                                                                                        msg.getOffset(),msg.getLength());
            Collection<? extends Address> flushParticipants=tuple.getVal1();
            Message response = new BytesMessage(flushRequester)
              .putHeader(this.id,new FlushHeader(FlushHeader.FLUSH_NOT_COMPLETED,fh.viewID))
              .setArray(marshal(flushParticipants, null));
            down_prot.down(response);
            if (log.isDebugEnabled())
                log.debug(local_addr + ": received START_FLUSH, responded with FLUSH_NOT_COMPLETED to " + flushRequester);
        }
    }

    private void rejectFlush(Collection<? extends Address> participants, long viewId) {
        if(participants == null)
            return;
        for (Address flushMember : participants) {
            if(flushMember == null)
                continue;
            Message reject = new BytesMessage(flushMember).setSrc(local_addr).setFlag(Message.Flag.OOB)
              .putHeader(this.id, new FlushHeader(FlushHeader.ABORT_FLUSH, viewId))
              .setArray(marshal(participants, null));
            down_prot.down(reject);
        }
    }

    public List<Integer> providedDownServices() {
        List<Integer> retval=new ArrayList<>(2);
        retval.add(Event.SUSPEND);
        retval.add(Event.RESUME);
        return retval;
    }

    private void sendBlockUpToChannel() {
        this.up(new Event(Event.BLOCK));
        sentUnblock.set(false);
    }

    private void sendUnBlockUpToChannel() {
        sentBlock.set(false);
        this.up(new Event(Event.UNBLOCK));
    }

    private boolean isCurrentFlushMessage(FlushHeader fh) {
        return fh.viewID == currentViewId();
    }

    private long currentViewId() {
        long viewId = -1;
        synchronized (sharedLock) {
            ViewId view = currentView.getViewId();
            if (view != null) {
                viewId = view.getId();
            }
        }
        return viewId;
    }

    private boolean onViewChange(View view) {
        boolean coordinatorLeft = false;
        View oldView;
        synchronized (sharedLock) {
            suspected.retainAll(view.getMembers());
            oldView = currentView;
            currentView = view;
            coordinatorLeft = !oldView.getMembers().isEmpty() && !view.getMembers().isEmpty()
                            && !view.containsMember(oldView.getCreator());
        }
        if (log.isDebugEnabled())
            log.debug(local_addr + ": installing view " + view);

        return coordinatorLeft;
    }

    private void onStopFlush() {
        if (stats && startFlushTime > 0) {
            long stopFlushTime = System.currentTimeMillis();
            totalTimeInFlush += (stopFlushTime - startFlushTime);
            if (numberOfFlushes > 0) {
                averageFlushDuration = totalTimeInFlush / (double) numberOfFlushes;
            }
            startFlushTime = 0;
        }

        if (log.isDebugEnabled())
           log.debug(local_addr
                   + ": received STOP_FLUSH, unblocking FLUSH.down() and sending UNBLOCK up");
        
        resetForNextFlush();
        if (sentUnblock.compareAndSet(false, true)) {
            // ensures that we do not repeat unblock event
            sendUnBlockUpToChannel();
        }       
    }


   private void resetForNextFlush() {
      synchronized (sharedLock) {
            flushCompletedMap.clear();
            flushNotCompletedMap.clear();
            flushMembers.clear();
            suspected.clear();
            flushCoordinator = null;
            flushCompleted = false;
        }        
        blockMutex.lock();
        try {
            isBlockingFlushDown = false;
            notBlockedDown.signalAll();
        } finally {
            blockMutex.unlock();
        }        
        flushInProgress.set(false);
   }

    /**
     * Starts the flush protocol
     * @param members List of participants in the flush protocol. Guaranteed to be non-null
     */
    private void onSuspend(final List<Address> members) {
        Collection<Address> participantsInFlush = null;
        synchronized (sharedLock) {
            flushCoordinator =local_addr;

            // start FLUSH only on group members that we need to flush
            participantsInFlush = members;
            participantsInFlush.retainAll(currentView.getMembers());
            flushMembers.clear();
            flushMembers.addAll(participantsInFlush);
            flushMembers.removeAll(suspected);
        }
        if (participantsInFlush.isEmpty()) {
            flush_promise.setResult(SUCCESS_START_FLUSH);
        } else {
            Message msg=new BytesMessage(null).setSrc(local_addr).setArray(marshal(participantsInFlush, null))
              .putHeader(this.id, new FlushHeader(FlushHeader.START_FLUSH, currentViewId()));
            down_prot.down(msg);
            log.debug("%s: flush coordinator is starting FLUSH with participants %s", local_addr, participantsInFlush);
        }
    }

    private void onResume(Event evt) {
        List<Address> members =evt.getArg();
        long viewID = currentViewId();
        boolean isParticipant = false;
        synchronized(sharedLock) {
            isParticipant = flushMembers.contains(local_addr) || (members != null && members.contains(local_addr));
        }
        if (members == null || members.isEmpty()) {
            Message msg = new EmptyMessage(null).setSrc(local_addr);
            // Cannot be OOB since START_FLUSH is not OOB
            // we have to FIFO order two subsequent flushes
            log.debug(local_addr + ": received RESUME, sending STOP_FLUSH to all");
            msg.putHeader(this.id, new FlushHeader(FlushHeader.STOP_FLUSH, viewID));
            down_prot.down(msg);
        } else {
            for (Address address : members) {
                Message msg = new EmptyMessage(address).setSrc(local_addr);
                // Cannot be OOB since START_FLUSH is not OOB
                // we have to FIFO order two subsequent flushes
                log.debug(local_addr + ": received RESUME, sending STOP_FLUSH to " + address);
                msg.putHeader(this.id, new FlushHeader(FlushHeader.STOP_FLUSH, viewID));
                down_prot.down(msg);
            }
        }
        if(isParticipant)
            waitForUnblock();        
    }

    private void onStartFlush(Address flushStarter, Message msg, FlushHeader fh) {
        if (stats) {
            startFlushTime = System.currentTimeMillis();
            numberOfFlushes += 1;
        }
        boolean proceed = false;
        boolean amIFlushInitiator = false;
        Tuple<Collection<? extends Address>,Digest> tuple=readParticipantsAndDigest(msg.getArray(),
                                                                                    msg.getOffset(),msg.getLength());
        synchronized (sharedLock) {
            amIFlushInitiator = flushStarter.equals(local_addr);
            if(!amIFlushInitiator){
               flushCoordinator = flushStarter;
               flushMembers.clear();
               if (tuple.getVal1() != null) {
                   flushMembers.addAll(tuple.getVal1());
               }               
               flushMembers.removeAll(suspected);
            }
            proceed = flushMembers.contains(local_addr);
        }

        if (proceed) {
            if (sentBlock.compareAndSet(false, true)) {
                // ensures that we do not repeat block event
                // and that we do not send block event to non participants
                sendBlockUpToChannel();
                blockMutex.lock();
                try {
                    isBlockingFlushDown = true;
                } finally {
                    blockMutex.unlock();
                }                
            } else {
                if (log.isDebugEnabled())
                    log.debug(local_addr + ": received START_FLUSH, but not sending BLOCK up");
            }

            Digest digest = (Digest) down_prot.down(Event.GET_DIGEST_EVT);
            Message start_msg = new BytesMessage(flushStarter)
              .putHeader(this.id, new FlushHeader(FlushHeader.FLUSH_COMPLETED, fh.viewID))
              .setArray(marshal(tuple.getVal1(), digest));
            down_prot.down(start_msg);
            log.debug(local_addr + ": received START_FLUSH, responded with FLUSH_COMPLETED to " + flushStarter);
        }

    }

    private void onFlushCompleted(Address address, final Message m, final FlushHeader header) {
        Message msg = null;
        boolean needsReconciliationPhase = false;
        boolean collision = false;
        final Tuple<Collection<? extends Address>,Digest> tuple=readParticipantsAndDigest(m.getArray(),
                                                                                          m.getOffset(),m.getLength());
        Digest digest = tuple.getVal2();
        synchronized (sharedLock) {
            flushCompletedMap.put(address, digest);
            flushCompleted = flushCompletedMap.size() >= flushMembers.size()
                            && !flushMembers.isEmpty()
                            && flushCompletedMap.keySet().containsAll(flushMembers);

            collision = !flushNotCompletedMap.isEmpty();
            if (log.isDebugEnabled())
                log.debug(local_addr + ": FLUSH_COMPLETED from " + address + ", completed "
                        + flushCompleted + ", flushMembers " + flushMembers
                        + ", flushCompleted " + flushCompletedMap.keySet());

            needsReconciliationPhase = enable_reconciliation && flushCompleted && hasVirtualSynchronyGaps();
            if (needsReconciliationPhase) {
                Digest d = findHighestSequences(currentView);
                reconcileOks.clear();
                msg = new BytesMessage(null, marshal(flushMembers, d)).setFlag(Message.Flag.OOB)
                  .putHeader(this.id, new FlushHeader(FlushHeader.FLUSH_RECONCILE, currentViewId()));

                log.debug("%s : reconciling flush mebers due to virtual synchrony gap, digest is %s, "
                            + "flush members are %s", local_addr, d, flushMembers);

                flushCompletedMap.clear();
            } else if (flushCompleted) {
                flushCompletedMap.clear();
            } else if (collision) {
                flushNotCompletedMap.clear();
                flushCompletedMap.clear();
            }
        }
        if (needsReconciliationPhase) {
            down_prot.down(msg);
        } else if (flushCompleted) {
            flush_promise.setResult(SUCCESS_START_FLUSH);
            if (log.isDebugEnabled())
                log.debug(local_addr + ": all FLUSH_COMPLETED received");
        } else if (collision) {
            // reject flush if we have at least one OK and at least one FAIL
            Runnable r =() -> rejectFlush(tuple.getVal1(), header.viewID);
            new Thread(r).start();
        }
    }

    private boolean hasVirtualSynchronyGaps() {
        ArrayList<Digest> digests=new ArrayList<>(flushCompletedMap.values());
        return !same(digests);
    }

    protected static boolean same(final List<Digest> digests) {
        if(digests == null) return false;
        Digest first=digests.get(0);
        for(int i=1; i < digests.size(); i++) {
            Digest current=digests.get(i);
            if(!first.equals(current))
                return false;
        }
        return true;
    }

    private Digest findHighestSequences(View view) {
        List<Digest> digests = new ArrayList<>(flushCompletedMap.values());
        return maxSeqnos(view,digests);
    }


    /** Returns a digest which contains, for all members of view, the highest delivered and received
     * seqno of all digests */
    protected static Digest maxSeqnos(final View view, List<Digest> digests) {
        if(view == null || digests == null)
            return null;

        MutableDigest digest=new MutableDigest(view.getMembersRaw());
        digests.forEach(digest::merge);
        return digest;
    }



    private void onSuspect(Collection<Address> addresses) {

        // handles FlushTest#testFlushWithCrashedFlushCoordinator
        boolean amINeighbourOfCrashedFlushCoordinator = false;
        ArrayList<Address> flushMembersCopy = null;
        synchronized (sharedLock) {
            boolean flushCoordinatorSuspected=addresses != null && addresses.contains(flushCoordinator);
            if (flushCoordinatorSuspected) {
                int indexOfCoordinator = flushMembers.indexOf(flushCoordinator);
                int myIndex = flushMembers.indexOf(local_addr);
                int diff = myIndex - indexOfCoordinator;
                amINeighbourOfCrashedFlushCoordinator = (diff == 1 || (myIndex == 0 && indexOfCoordinator == flushMembers.size()));
                if (amINeighbourOfCrashedFlushCoordinator) {
                    flushMembersCopy = new ArrayList<>(flushMembers);
                }
            }
        }
        if (amINeighbourOfCrashedFlushCoordinator) {
            if (log.isDebugEnabled())
                log.debug(local_addr + ": flush coordinator " + flushCoordinator + " suspected,"
                        + " I am the neighbor, completing the flush ");

            onResume(new Event(Event.RESUME, flushMembersCopy));
        }

        // handles FlushTest#testFlushWithCrashedNonCoordinators
        boolean flushOkCompleted = false;
        Message m = null;
        long viewID = 0;
        synchronized (sharedLock) {
            suspected.addAll(addresses);
            flushMembers.removeAll(suspected);
            viewID = currentViewId();
            flushOkCompleted = !flushCompletedMap.isEmpty() && flushCompletedMap.keySet().containsAll(flushMembers);
            if (flushOkCompleted) {
                m = new BytesMessage(flushCoordinator).setSrc(local_addr);
            }
            log.debug(local_addr + ": suspects: " + addresses + ", completed " + flushOkCompleted
                        + ", flushOkSet " + flushCompletedMap + ", flushMembers " + flushMembers);
        }
        if (flushOkCompleted) {
            Digest digest = (Digest) down_prot.down(Event.GET_DIGEST_EVT);
            m.putHeader(this.id, new FlushHeader(FlushHeader.FLUSH_COMPLETED, viewID)).setArray(marshal(null, digest));
            down_prot.down(m);

            if (log.isDebugEnabled())
                log.debug(local_addr + ": sent FLUSH_COMPLETED message to " + flushCoordinator);
        }
    }

    protected static ByteArray marshal(final Collection<? extends Address> participants, final Digest digest) {
        final ByteArrayDataOutputStream out=new ByteArrayDataOutputStream(512);
        try {
            Util.writeAddresses(participants, out);
            Util.writeStreamable(digest,out);
            return out.getBuffer();
        }
        catch(Exception ex) {
            return null;
        }
    }


    protected  Tuple<Collection<? extends Address>,Digest> readParticipantsAndDigest(byte[] buffer, int offset, int length) {
        if(buffer == null) return null;
        try {
            DataInput in=new ByteArrayDataInputStream(buffer, offset, length);
            Collection<Address> participants=Util.readAddresses(in, ArrayList::new);
            Digest digest=Util.readStreamable(Digest::new, in);
            return new Tuple<>(participants, digest);
        }
        catch(Exception ex) {
            log.error("%s: failed reading particpants and digest from message: %s", local_addr, ex);
            return null;
        }
    }


    private static final class FlushStartResult {
        private final Boolean result;
        private final Exception failureCause;
      

      private FlushStartResult(Boolean result, Exception failureCause) {
         this.result = result;
         this.failureCause = failureCause;
      }

      public Boolean getResult() {
         return result;
      }
      
      public boolean failed(){
         return result == Boolean.FALSE;
      }

      public Exception getFailureCause() {
         return failureCause;
      }
    }

    public static class FlushHeader extends Header {
        public static final byte START_FLUSH         = 0;
        public static final byte STOP_FLUSH          = 2;
        public static final byte FLUSH_COMPLETED     = 3;
        public static final byte ABORT_FLUSH         = 5;
        public static final byte FLUSH_BYPASS        = 6;
        public static final byte FLUSH_RECONCILE     = 7;
        public static final byte FLUSH_RECONCILE_OK  = 8;
        public static final byte FLUSH_NOT_COMPLETED = 9;

        protected byte                type;
        protected long                viewID;


        public FlushHeader() {
            this(START_FLUSH, 0);
        }

        public FlushHeader(byte type) {
            this.type=type;
        }

        public FlushHeader(byte type, long viewID) {
            this(type);
            this.viewID=viewID;
        }

        public short getMagicId() {return 64;}
        public Supplier<? extends Header> create() {return FlushHeader::new;}
        public byte getType()                             {return type;}
        public long getViewID()                           {return viewID;}

        @Override
        public int serializedSize() {
            return Global.BYTE_SIZE + Global.LONG_SIZE; // type and viewId
        }


        public String toString() {
            switch (type) {
                case START_FLUSH:
                    return "FLUSH[type=START_FLUSH,viewId=" + viewID;
                case STOP_FLUSH:
                    return "FLUSH[type=STOP_FLUSH,viewId=" + viewID + "]";
                case ABORT_FLUSH:
                    return "FLUSH[type=ABORT_FLUSH,viewId=" + viewID + "]";
                case FLUSH_COMPLETED:
                    return "FLUSH[type=FLUSH_COMPLETED,viewId=" + viewID + "]";
                case FLUSH_BYPASS:
                    return "FLUSH[type=FLUSH_BYPASS,viewId=" + viewID + "]";
                case FLUSH_RECONCILE:
                    return "FLUSH[type=FLUSH_RECONCILE,viewId=" + viewID;
                case FLUSH_RECONCILE_OK:
                    return "FLUSH[type=FLUSH_RECONCILE_OK,viewId=" + viewID + "]";
                default:
                    return "[FLUSH: unknown type (" + type + ")]";
            }
        }

        @Override
        public void writeTo(DataOutput out) throws IOException {
            out.writeByte(type);
            out.writeLong(viewID);
        }

        @Override
        public void readFrom(DataInput in) throws IOException {
            type = in.readByte();
            viewID = in.readLong();
        }
    }
}
