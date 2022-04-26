package org.jgroups.stack;

import org.jgroups.Address;
import org.jgroups.Message;
import org.jgroups.PhysicalAddress;
import org.jgroups.Version;
import org.jgroups.annotations.Component;
import org.jgroups.annotations.ManagedAttribute;
import org.jgroups.annotations.ManagedOperation;
import org.jgroups.blocks.cs.*;
import org.jgroups.conf.AttributeType;
import org.jgroups.jmx.JmxConfigurator;
import org.jgroups.jmx.ReflectUtils;
import org.jgroups.logging.Log;
import org.jgroups.logging.LogFactory;
import org.jgroups.protocols.PingData;
import org.jgroups.util.*;

import javax.net.ssl.*;
import java.io.DataInput;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Router for TCP based group comunication (using layer TCP instead of UDP). Instead of the TCP
 * layer sending packets point-to-point to each other member, it sends the packet to the router
 * which - depending on the target address - multicasts or unicasts it to the group / or single member.
 * <p/>
 * This class is especially interesting for applets which cannot directly make connections (neither
 * UDP nor TCP) to a host different from the one they were loaded from. Therefore, an applet would
 * create a normal channel plus protocol stack, but the bottom layer would have to be the TCP layer
 * which sends all packets point-to-point (over a TCP connection) to the router, which in turn
 * forwards them to their end location(s) (also over TCP). A centralized router would therefore have
 * to be running on the host the applet was loaded from.
 * <p/>
 * An alternative for running JGroups in an applet (IP multicast is not allows in applets as of
 * 1.2), is to use point-to-point UDP communication via the gossip server. However, then the appplet
 * has to be signed which involves additional administrative effort on the part of the user.
 * <p/>
 * Note that a GossipRouter is also a good way of running JGroups in Amazon's EC2 environment which (as of summer 09)
 * doesn't support IP multicasting.
 * @author Bela Ban
 * @author Vladimir Blagojevic
 * @author Ovidiu Feodorov <ovidiuf@users.sourceforge.net>
 * @since 2.1.1
 */
public class GossipRouter extends ReceiverAdapter implements ConnectionListener, DiagnosticsHandler.ProbeHandler {
    @ManagedAttribute(description="The bind address which should be used by the GossipRouter.")
    protected InetAddress          bind_addr;

    @ManagedAttribute(description="server port on which the GossipRouter accepts client connections", writable=true)
    protected int                  port=12001;
    
    @ManagedAttribute(description="time (in msecs) until gossip entry expires. 0 disables expiration.",
      writable=true,type=AttributeType.TIME)
    protected long                 expiry_time;

    @ManagedAttribute(description="Time (in ms) for setting SO_LINGER on sockets returned from accept(). 0 means do not set SO_LINGER"
      ,type=AttributeType.TIME)
    protected long                 linger_timeout=2000L;

    @ManagedAttribute(description="Time (in ms) for SO_TIMEOUT on sockets returned from accept(). 0 means don't set SO_TIMEOUT"
      ,type=AttributeType.TIME)
    protected long                 sock_read_timeout;

    protected ThreadFactory        thread_factory=new DefaultThreadFactory("gossip", false, true);

    protected SocketFactory        socket_factory=new DefaultSocketFactory();

    @ManagedAttribute(description="The max queue size of backlogged connections")
    protected int                  backlog=1000;

    @ManagedAttribute(description="Initial size of the TCP/NIO receive buffer (in bytes)")
    protected int                  recv_buf_size;

    @ManagedAttribute(description="Expose GossipRouter via JMX")
    protected boolean              jmx=true;

    @ManagedAttribute(description="Use non-blocking IO (true) or blocking IO (false). Cannot be changed at runtime")
    protected boolean              use_nio;

    @ManagedAttribute(description="Handles client disconnects: sends SUSPECT message to all other members of that group")
    protected boolean              emit_suspect_events=true;

    @ManagedAttribute(description="Dumps messages (dest/src/length/headers) to stdout")
    protected DumpMessages         dump_msgs;

    @ManagedAttribute(description="The max number of bytes a message can have. If greater, an exception will be " +
      "thrown. 0 disables this", type=AttributeType.BYTES)
    protected int                  max_length;
    protected BaseServer           server;
    protected final AtomicBoolean  running=new AtomicBoolean(false);
    protected Timer                timer;
    protected final Log            log=LogFactory.getLog(this.getClass());

    @Component
    protected DiagnosticsHandler   diag;

    // mapping between groups and <member address> - <physical addr / logical name> pairs
    protected final Map<String,ConcurrentMap<Address,Entry>> address_mappings=new ConcurrentHashMap<>();

    // to cache output streams for serialization (https://issues.redhat.com/browse/JGRP-2576)
    protected final Map<Address,ByteArrayDataOutputStream>   output_streams=new ConcurrentHashMap<>();

    protected static final BiConsumer<Short,Message> MSG_CONSUMER=(version,msg)
      -> System.out.printf("dst=%s src=%s (%d bytes): hdrs= %s\n", msg.dest(), msg.src(), msg.getLength(), msg.printHeaders());


    public GossipRouter(String bind_addr, int local_port) {
        this.port=local_port;
        try {
            this.bind_addr=bind_addr != null? InetAddress.getByName(bind_addr) : null;
            init();
        }
        catch(UnknownHostException e) {
            log.error("failed setting bind address %s: %s", bind_addr, e);
        }
    }

    public GossipRouter(InetAddress bind_addr, int local_port) {
        this.port=local_port;
        this.bind_addr=bind_addr;
        init();
    }

    public Address       localAddress()                     {return server.localAddress();}
    public String        bindAddress()                      {return bind_addr != null? bind_addr.toString() : null;}
    public GossipRouter  bindAddress(InetAddress addr)      {this.bind_addr=addr; return this;}
    public int           port()                             {return port;}
    public GossipRouter  port(int port)                     {this.port=port; return this;}
    public long          expiryTime()                       {return expiry_time;}
    public GossipRouter  expiryTime(long t)                 {this.expiry_time=t; return this;}
    public long          lingerTimeout()                    {return linger_timeout;}
    public GossipRouter  lingerTimeout(long t)              {this.linger_timeout=t; return this;}
    public long          socketReadTimeout()                {return sock_read_timeout;}
    public GossipRouter  socketReadTimeout(long t)          {this.sock_read_timeout=t; return this;}
    public int           recvBufferSize()                   {return recv_buf_size;}
    public GossipRouter  recvBufferSize(int s)              {recv_buf_size=s; return this;}
    public ThreadFactory threadPoolFactory()                {return thread_factory;}
    public GossipRouter  threadPoolFactory(ThreadFactory f) {this.thread_factory=f; return this;}
    public SocketFactory socketFactory()                    {return socket_factory;}
    public GossipRouter  socketFactory(SocketFactory sf)    {this.socket_factory=sf; return this;}
    public int           backlog()                          {return backlog;}
    public GossipRouter  backlog(int backlog)               {this.backlog=backlog; return this;}
    public boolean       jmx()                              {return jmx;}
    public GossipRouter  jmx(boolean flag)                  {jmx=flag; return this;}
    public boolean       useNio()                           {return use_nio;}
    public GossipRouter  useNio(boolean flag)               {use_nio=flag; return this;}
    public boolean       emitSuspectEvents()                {return emit_suspect_events;}
    public GossipRouter  emitSuspectEvents(boolean flag)    {emit_suspect_events=flag; return this;}
    public DumpMessages  dumpMessages()                     {return dump_msgs;}
    public GossipRouter  dumpMessages(DumpMessages flag)    {dump_msgs=flag; return this;}
    public GossipRouter  dumpMessages(boolean d)            {dump_msgs=d? DumpMessages.ALL : DumpMessages.NONE; return this;}
    public int           maxLength()                        {return max_length;}
    public GossipRouter  maxLength(int len)                 {max_length=len; if(server != null) server.setMaxLength(len);
                                                             return this;}
    public DiagnosticsHandler diagHandler()                 {return diag;}



    @ManagedAttribute(description="operational status", name="running")
    public boolean       running()                          {return running.get();}


    public GossipRouter init() {
        diag=new DiagnosticsHandler(log, socket_factory, thread_factory)
          .registerProbeHandler(this)
          .printHeaders(b -> String.format("GossipRouter [addr=%s, cluster=GossipRouter, version=%s]\n",
                                           localAddress(), Version.description));
        return this;
    }


    /**
     * Lifecycle operation. Called after create(). When this method is called, the managed attributes
     * have already been set.<br>
     * Brings the Router into a fully functional state.
     */
    @ManagedOperation(description="Lifecycle operation. Called after create(). When this method is called, "
            + "the managed attributes have already been set. Brings the Router into a fully functional state.")
    public GossipRouter start() throws Exception {
        if(!running.compareAndSet(false, true))
            return this;
        if(jmx)
            JmxConfigurator.register(this, Util.getMBeanServer(), "jgroups:name=GossipRouter");

        if(diag.isEnabled()) {
            StackType ip_version=bind_addr instanceof Inet6Address? StackType.IPv6 : StackType.IPv4;
            Configurator.setDefaultAddressValues(diag, ip_version);
            diag.start();
        }

        server=use_nio? new NioServer(thread_factory, socket_factory, bind_addr, port, port, null, 0, recv_buf_size)
          : new TcpServer(thread_factory, socket_factory, bind_addr, port, port, null, 0, recv_buf_size);
        server.receiver(this).setMaxLength(max_length);
        server.start();
        server.addConnectionListener(this);
        Runtime.getRuntime().addShutdownHook(new Thread(GossipRouter.this::stop));
        return this;
    }


    /**
     * Always called before destroy(). Close connections and frees resources.
     */
    public void stop() {
        if(!running.compareAndSet(true, false))
            return;

        try {
            JmxConfigurator.unregister(this, Util.getMBeanServer(), "jgroups:name=GossipRouter");
        }
        catch(Exception ex) {
            log.error(Util.getMessage("MBeanDeRegistrationFailed"), ex);
        }
        Util.close(diag, server);
        log.debug("router stopped");
    }


    @ManagedOperation(description="Dumps the contents of the routing table")
    public String dumpRoutingTable() {
        return server.printConnections();
    }


    @ManagedOperation(description="Dumps the address mappings")
    public String dumpAddressMappings() {
        StringBuilder sb=new StringBuilder();
        for(Map.Entry<String,ConcurrentMap<Address,Entry>> entry: address_mappings.entrySet()) {
            String group=entry.getKey();
            Map<Address,Entry> val=entry.getValue();
            if(val == null)
                continue;
            sb.append(group).append(":\n");
            for(Map.Entry<Address,Entry> entry2: val.entrySet()) {
                Address logical_addr=entry2.getKey();
                Entry val2=entry2.getValue();
                if(val2 == null)
                    continue;
                sb.append(String.format("  %s: %s (client_addr: %s, uuid:%s)\n", val2.logical_name, val2.phys_addr, val2.client_addr, logical_addr));
            }
        }
        return sb.toString();
    }


    @Override public void receive(Address sender, byte[] buf, int offset, int length) {
        receive(sender, ByteBuffer.wrap(buf, offset, length));
    }

    @Override
    public void receive(Address sender, ByteBuffer buf) {
        int original_pos=buf.position();
        GossipType type;
        try {
            type=GossipType.values()[buf.get()];
        }
        catch(Exception ex) {
            log.error("failed reading data from %s: %s", sender, ex);
            return;
        }

        switch(type) {
            case REGISTER:
                DataInput in=new ByteArrayDataInputStream(buf);
                handleRegister(sender, in);
                break;

            case MESSAGE:
                // we already read the type, now read group and dest (minimal info required to route the message)
                // this way, we don't need to copy the buffer
                try {
                    in=new ByteArrayDataInputStream(buf);
                    String group=Bits.readString(in);
                    Address dest=Util.readAddress(in);
                    route(group, dest, buf.position(original_pos));

                    if(dump_msgs == DumpMessages.ALL) {
                        ByteArrayDataInputStream input=new ByteArrayDataInputStream(buf);
                        GossipData data=new GossipData();
                        data.readFrom(input);
                        dump(data);
                    }
                }
                catch(Throwable t) {
                    log.error(Util.getMessage("FailedReadingRequest"), t);
                    return;
                }
                break;

            case GET_MBRS:
                in=new ByteArrayDataInputStream(buf);
                handleGetMembersRequest(sender, in);
                break;

            case UNREGISTER:
                in=new ByteArrayDataInputStream(buf);
                handleUnregister(in);
                break;
        }
    }

    public void receive(Address sender, DataInput in) throws Exception {
        GossipType type=GossipType.values()[in.readByte()];

        GossipData request=null;
        switch(type) {
            case REGISTER:
                handleRegister(sender, in);
                break;

            case MESSAGE:
                try {
                    // inefficient: we should transfer bytes from input stream to output stream, but that is not
                    // available natively
                    if((request=readRequest(in, type)) != null) {
                        ByteArrayDataOutputStream out=getOutputStream(request.sender, request.serializedSize());
                        out.position(0);
                        request.writeTo(out);
                        route(request.group, request.addr, out.buffer(), 0, out.position());
                        if(dump_msgs == DumpMessages.ALL)
                            dump(request);
                    }
                }
                catch(Throwable t) {
                    log.error(Util.getMessage("FailedReadingRequest"), t);
                    return;
                }
                break;

            case GET_MBRS:
                handleGetMembersRequest(sender, in);
                break;

            case UNREGISTER:
                handleUnregister(in);
                break;
        }
    }

    public Map<String,String> handleProbe(String... keys) {
        Map<String,String> map=new TreeMap<>();
        for(String key: keys) {
            if(key.startsWith("ops")) {
                map.put(key, ReflectUtils.listOperations(getClass()));
                continue;
            }
            if(key.startsWith("op") || key.startsWith("invoke")) {
                int index=key.indexOf('=');
                if(index == -1)
                    throw new IllegalArgumentException(String.format("\\= not found in operation %s", key));
                String op=key.substring(index + 1).trim();
                try {
                    ReflectUtils.invokeOperation(map, op, this);
                }
                catch(Exception ex) {
                    throw new IllegalArgumentException(String.format("operation %s failed: %s", op, ex));
                }
                continue;
            }
            if(key.startsWith("jmx")) {
                int index=key.indexOf('=');
                String tmp;
                if(index == -1)
                    tmp=null;
                else
                    tmp=key.substring(index+1);
                ReflectUtils.handleAttributeAccess(map, tmp, this);
                continue;
            }
            if(key.startsWith("keys")) {
                StringBuilder sb=new StringBuilder();
                for(DiagnosticsHandler.ProbeHandler handler : diag.getProbeHandlers()) {
                    String[] tmp=handler.supportedKeys();
                    if(tmp != null && tmp.length > 0) {
                        for(String s : tmp)
                            sb.append(s).append(" ");
                    }
                }
                map.put(key, sb.toString());
            }
        }
        return map;
    }

    public String[] supportedKeys() {
        return new String[]{"ops", "op", "invoke", "keys"};
    }

    protected ByteArrayDataOutputStream getOutputStream(Address mbr, int size) {
        return output_streams.computeIfAbsent(mbr, addr -> new ByteArrayDataOutputStream(size));
    }

    protected void handleRegister(Address sender, DataInput in) {
        GossipData req=readRequest(in, GossipType.REGISTER);
        if(req != null) {
            String          group=req.getGroup();
            Address         addr=req.getAddress();
            PhysicalAddress phys_addr=req.getPhysicalAddress();
            String          logical_name=req.getLogicalName();
            addAddressMapping(sender, group, addr, phys_addr, logical_name);
            if(log.isDebugEnabled())
                log.debug("added %s (%s) to group %s", logical_name, phys_addr, group);
            if(dump_msgs == DumpMessages.REGISTRATION || dump_msgs == DumpMessages.ALL)
                System.out.printf("added %s (%s) to group %s\n", logical_name, phys_addr, group);
        }
    }

    protected void handleUnregister(DataInput in) {
        GossipData req=readRequest(in, GossipType.UNREGISTER);
        if(req != null)
            removeAddressMapping(req.getGroup(), req.getAddress());
    }

    protected void handleGetMembersRequest(Address sender, DataInput in) {
        GossipData req=readRequest(in, GossipType.GET_MBRS);
        if(req == null)
            return;
        GossipData rsp=new GossipData(GossipType.GET_MBRS_RSP, req.getGroup(), null);
        Map<Address,Entry> members=address_mappings.get(req.getGroup());
        if(members != null) {
            for(Map.Entry<Address,Entry> entry : members.entrySet()) {
                Address logical_addr=entry.getKey();
                PhysicalAddress phys_addr=entry.getValue().phys_addr;
                String logical_name=entry.getValue().logical_name;
                PingData data=new PingData(logical_addr, true, logical_name, phys_addr);
                rsp.addPingData(data);
            }
        }

        if(dump_msgs == DumpMessages.ALL || log.isDebugEnabled()) {
            String rsps=rsp.ping_data == null? null
              : rsp.ping_data.stream().map(r -> String.format("%s (%s)", r.getLogicalName(), r.getPhysicalAddr()))
              .collect(Collectors.joining(", "));
            if(rsps != null) {
                if(log.isDebugEnabled())
                    log.debug("get(%s) -> %s", req.getGroup(), rsps);
                if(dump_msgs == DumpMessages.ALL)
                    System.out.printf("get(%s) -> %s\n", req.getGroup(), rsps);
            }
        }

        ByteArrayDataOutputStream out=new ByteArrayDataOutputStream(rsp.serializedSize());
        try {
            rsp.writeTo(out);
            server.send(sender, out.buffer(), 0, out.position());
        }
        catch(Exception ex) {
            log.error("failed sending %d to %s: %s", GossipType.GET_MBRS_RSP, sender, ex);
        }
    }

    protected static void dump(GossipData data) {
        Util.parse(data.buffer, data.offset, data.length, MSG_CONSUMER, null, false);
    }

    @Override
    public void connectionClosed(Connection conn) {
        removeFromAddressMappings(conn.peerAddress());
    }

    @Override
    public void connectionEstablished(Connection conn) {
        log.debug("connection to %s established", conn.peerAddress());
    }

    protected GossipData readRequest(DataInput in) {
        GossipData data=new GossipData();
        try {
            data.readFrom(in);
            return data;
        }
        catch(Exception ex) {
            log.error(Util.getMessage("FailedReadingRequest"), ex);
            return null;
        }
    }

    protected GossipData readRequest(DataInput in, GossipType type) {
        GossipData data=new GossipData(type);
        try {
            data.readFrom(in, false);
            return data;
        }
        catch(Exception ex) {
            log.error(Util.getMessage("FailedReadingRequest"), ex);
            return null;
        }
    }


    protected void addAddressMapping(Address sender, String group, Address addr, PhysicalAddress phys_addr, String logical_name) {
        ConcurrentMap<Address,Entry> m=address_mappings.get(group);
        if(m == null) {
            ConcurrentMap<Address,Entry> existing=this.address_mappings.putIfAbsent(group, m=new ConcurrentHashMap<>());
            if(existing != null)
                m=existing;
        }
        m.put(addr, new Entry(sender, phys_addr, logical_name));
    }

    protected void removeAddressMapping(String group, Address addr) {
        Map<Address,Entry> m=address_mappings.get(group);
        if(m == null)
            return;
        Entry e=m.get(addr);
        if(e != null) {
            if(log.isDebugEnabled())
                log.debug("removed %s (%s) from group %s", e.logical_name, e.phys_addr, group);
            if(dump_msgs == DumpMessages.REGISTRATION || dump_msgs == DumpMessages.ALL)
                System.out.printf("removed %s (%s) from group %s\n", e.logical_name, e.phys_addr, group);
        }
        if(m.remove(addr) != null && m.isEmpty())
            address_mappings.remove(group);
        output_streams.remove(addr);
    }


    protected void removeFromAddressMappings(Address client_addr) {
        if(client_addr == null) return;
        Set<Tuple<String,Address>> suspects=null; // group/address pairs
        for(Map.Entry<String,ConcurrentMap<Address,Entry>> entry: address_mappings.entrySet()) {
            ConcurrentMap<Address,Entry> map=entry.getValue();
            for(Map.Entry<Address,Entry> entry2: map.entrySet()) {
                Entry e=entry2.getValue();
                if(client_addr.equals(e.client_addr)) {
                    map.remove(entry2.getKey());
                    output_streams.remove(entry2.getKey());
                    log.debug("connection to %s closed", client_addr);
                    if(log.isDebugEnabled())
                        log.debug("removed %s (%s) from group %s", e.logical_name, e.phys_addr, entry.getKey());
                    if(dump_msgs == DumpMessages.REGISTRATION || dump_msgs == DumpMessages.ALL)
                        System.out.printf("removed %s (%s) from group %s\n", e.logical_name, e.phys_addr, entry.getKey());
                    if(map.isEmpty())
                        address_mappings.remove(entry.getKey());
                    if(suspects == null) suspects=new HashSet<>();
                    suspects.add(new Tuple<>(entry.getKey(), entry2.getKey()));
                    break;
                }
            }
        }
        if(emit_suspect_events && suspects != null && !suspects.isEmpty()) {
           for(Tuple<String,Address> suspect: suspects) {
               String group=suspect.getVal1();
               Address addr=suspect.getVal2();
               ConcurrentMap<Address,Entry> map=address_mappings.get(group);
               if(map == null)
                   continue;
               GossipData data=new GossipData(GossipType.SUSPECT, group, addr);
               sendToAllMembersInGroup(map.entrySet(), data);
           }
        }
    }


    protected void route(String group, Address dest, byte[] msg, int offset, int length) {
        ConcurrentMap<Address,Entry> map=address_mappings.get(group);
        if(map == null)
            return;
        if(dest != null) { // unicast
            Entry entry=map.get(dest);
            if(entry != null)
                sendToMember(entry.client_addr, msg, offset, length);
            else
                log.warn("dest %s in cluster %s not found", dest, group);
        }
        else {             // multicast - send to all members in group
            Set<Map.Entry<Address,Entry>> dests=map.entrySet();
            sendToAllMembersInGroup(dests, msg, offset, length);
        }
    }

    protected void route(String group, Address dest, ByteBuffer buf) {
        ConcurrentMap<Address,Entry> map=address_mappings.get(group);
        if(map == null)
            return;
        if(dest != null) { // unicast
            Entry entry=map.get(dest);
            if(entry != null)
                sendToMember(entry.client_addr, buf);
            else
                log.warn("dest %s in cluster %s not found", dest, group);
        }
        else {             // multicast - send to all members in group
            Set<Map.Entry<Address,Entry>> dests=map.entrySet();
            sendToAllMembersInGroup(dests, buf);
        }
    }



    protected void sendToAllMembersInGroup(Set<Map.Entry<Address,Entry>> dests, GossipData request) {
        ByteArrayDataOutputStream out=new ByteArrayDataOutputStream(request.serializedSize());
        try {
            request.writeTo(out);
        }
        catch(Exception ex) {
            log.error("failed marshalling gossip data %s: %s; dropping request", request, ex);
            return;
        }

        for(Map.Entry<Address,Entry> entry: dests) {
            Entry e=entry.getValue();
            if(e == null /* || e.phys_addr == null */)
                continue;

            try {
                server.send(e.client_addr, out.buffer(), 0, out.position());
            }
            catch(Exception ex) {
                log.error("failed sending message to %s (%s): %s", e.logical_name, e.phys_addr, ex);
            }
        }
    }


    protected void sendToAllMembersInGroup(Set<Map.Entry<Address,Entry>> dests, byte[] buf, int offset, int len) {
        for(Map.Entry<Address,Entry> entry: dests) {
            Entry e=entry.getValue();
            if(e == null /* || e.phys_addr == null */)
                continue;

            try {
                server.send(e.client_addr, buf, offset, len);
            }
            catch(Exception ex) {
                log.error("failed sending message to %s (%s): %s", e.logical_name, e.phys_addr, ex);
            }
        }
    }

    protected void sendToAllMembersInGroup(Set<Map.Entry<Address,Entry>> dests, ByteBuffer buf) {
        for(Map.Entry<Address,Entry> entry: dests) {
            Entry e=entry.getValue();
            if(e == null /* || e.phys_addr == null */)
                continue;

            try {
                server.send(e.client_addr, buf.duplicate());
            }
            catch(Exception ex) {
                log.error("failed sending message to %s (%s): %s", e.logical_name, e.phys_addr, ex);
            }
        }
    }


    protected void sendToMember(Address dest, GossipData request) {
        ByteArrayDataOutputStream out=new ByteArrayDataOutputStream(request.serializedSize());
        try {
            request.writeTo(out);
            server.send(dest, out.buffer(), 0, out.position());
        }
        catch(Exception ex) {
            log.error("failed sending unicast message to %s: %s", dest, ex);
        }
    }

    protected void sendToMember(Address dest, ByteBuffer buf) {
        try {
            server.send(dest, buf);
        }
        catch(Exception ex) {
            log.error("failed sending unicast message to %s: %s", dest, ex);
        }
    }

    protected void sendToMember(Address dest, byte[] buf, int offset, int len) {
        try {
            server.send(dest, buf, offset, len);
        }
        catch(Exception ex) {
            log.error("failed sending unicast message to %s: %s", dest, ex);
        }
    }


    protected static class Entry {
        protected final PhysicalAddress phys_addr;
        protected final String          logical_name;
        protected final Address         client_addr; // address of the client which registered an item

        public Entry(Address client_addr, PhysicalAddress phys_addr, String logical_name) {
            this.phys_addr=phys_addr;
            this.logical_name=logical_name;
            this.client_addr=client_addr;
        }

        public String toString() {return String.format("client=%s, name=%s, addr=%s", client_addr, logical_name, phys_addr);}
    }



    /**
     * Prints startup information.
     */
    private void printStartupInfo() {
        System.out.println("GossipRouter started at " + new Date());

        System.out.print("Listening on port " + port);
        System.out.println(" bound on address " + server.localAddress());

        System.out.print("Backlog is " + backlog);
        System.out.print(", linger timeout is " + linger_timeout);
        System.out.println(", and read timeout is " + sock_read_timeout);
    }




    public static void main(String[] args) throws Exception {
        int                    port=12001;
        int                    backlog=0, recv_buf_size=0, max_length=0;
        long                   soLinger=-1;
        long                   soTimeout=-1;
        long                   expiry_time=60000;
        boolean                diag_enabled=true, diag_enable_udp=true, diag_enable_tcp=false;
        InetAddress            diag_mcast_addr=null, diag_bind_addr=null;
        int                    diag_port=7500, diag_port_range=50, diag_ttl=8;
        List<NetworkInterface> diag_bind_interfaces=null;
        String                 diag_passcode=null;

        String tls_protocol=null;
        String tls_provider=null;
        String tls_keystore_path=null;
        String tls_keystore_password=null;
        String tls_keystore_type=null;
        String tls_keystore_alias=null;
        String tls_truststore_path=null;
        String tls_truststore_password=null;
        String tls_truststore_type=null;
        TLSClientAuth tls_client_auth=TLSClientAuth.NONE;
        List<SNIMatcher> tls_sni_matchers=new ArrayList<>();

        long start=System.currentTimeMillis();
        GossipRouter router;
        String bind_addr=null;
        boolean jmx=false, nio=false, suspects=true;
        DumpMessages dump_msgs = DumpMessages.NONE;

        for(int i=0; i < args.length; i++) {
            String arg=args[i];
            if("-port".equals(arg)) {
                port=Integer.parseInt(args[++i]);
                continue;
            }
            if("-bindaddress".equals(arg) || "-bind_addr".equals(arg)) {
                bind_addr=args[++i];
                continue;
            }
            if("-backlog".equals(arg)) {
                backlog=Integer.parseInt(args[++i]);
                continue;
            }
            if("-recv_buf_size".equals(args[i])) {
                recv_buf_size=Integer.parseInt(args[++i]);
                continue;
            }
            if("-expiry".equals(arg)) {
                expiry_time=Long.parseLong(args[++i]);
                continue;
            }
            if("-jmx".equals(arg)) {
                jmx=Boolean.parseBoolean(args[++i]);
                continue;
            }
            if("-solinger".equals(arg)) {
                soLinger=Long.parseLong(args[++i]);
                continue;
            }
            if("-sotimeout".equals(arg)) {
                soTimeout=Long.parseLong(args[++i]);
                continue;
            }
            if("-nio".equals(arg)) {
                nio=Boolean.parseBoolean(args[++i]);
                continue;
            }
            if("-suspect".equals(arg)) {
                suspects=Boolean.parseBoolean(args[++i]);
                continue;
            }
            if("-dump_msgs".equals(arg)) {
                dump_msgs=DumpMessages.parse(args[++i]);
                continue;
            }
            if("-max_length".equals(arg)) {
                max_length=Integer.parseInt(args[++i]);
                continue;
            }
            if("-diag_enabled".equals(arg)) {
                diag_enabled=Boolean.parseBoolean(args[++i]);
                continue;
            }
            if("-diag_enable_udp".equals(arg)) {
                diag_enable_udp=Boolean.parseBoolean(args[++i]);
                continue;
            }
            if("-diag_enable_tcp".equals(arg)) {
                diag_enable_tcp=Boolean.parseBoolean(args[++i]);
                continue;
            }
            if("-diag_mcast_addr".equals(arg)) {
                diag_mcast_addr=InetAddress.getByName(args[++i]);
                continue;
            }
            if("-diag_bind_addr".equals(arg)) {
                diag_bind_addr=InetAddress.getByName(args[++i]);
                continue;
            }
            if("-diag_port".equals(arg)) {
                diag_port=Integer.parseInt(args[++i]);
                continue;
            }
            if("-diag_port_range".equals(arg)) {
                diag_port_range=Integer.parseInt(args[++i]);
                continue;
            }
            if("-diag_ttl".equals(arg)) {
                diag_ttl=Integer.parseInt(args[++i]);
                continue;
            }
            if("-diag_bind_interfaces".equals(arg)) {
                diag_bind_interfaces=Util.parseInterfaceList(args[++i]);
                continue;
            }
            if("-diag_passcode".equals(arg)) {
                diag_passcode=args[++i];
                continue;
            }
            if("-tls_protocol".equals(arg)) {
                tls_protocol=args[++i];
                continue;
            }
            if("-tls_provider".equals(arg)) {
                tls_provider=args[++i];
                continue;
            }
            if("-tls_keystore_path".equals(arg)) {
                tls_keystore_path=args[++i];
                continue;
            }
            if("-tls_keystore_password".equals(arg)) {
                tls_keystore_password=args[++i];
                continue;
            }
            if("-tls_keystore_type".equals(arg)) {
                tls_keystore_type=args[++i];
                continue;
            }
            if("-tls_keystore_alias".equals(arg)) {
                tls_keystore_alias=args[++i];
                continue;
            }
            if("-tls_truststore_path".equals(arg)) {
                tls_truststore_path=args[++i];
                continue;
            }
            if("-tls_truststore_password".equals(arg)) {
                tls_truststore_password=args[++i];
                continue;
            }
            if("-tls_truststore_type".equals(arg)) {
                tls_truststore_type=args[++i];
                continue;
            }
            if("-tls_client_auth".equals(arg)) {
                tls_client_auth=TLSClientAuth.valueOf(args[++i].toUpperCase());
                continue;
            }
            if("-tls_sni_matcher".equals(arg)) {
                tls_sni_matchers.add(SNIHostName.createSNIMatcher(args[++i]));
                continue;
            }
            help();
            return;
        }
        if(tls_protocol != null && nio)
            // Doesn't work yet
            throw new IllegalArgumentException("Cannot use NIO with TLS");

        router=new GossipRouter(bind_addr, port)
          .jmx(jmx).expiryTime(expiry_time)
          .useNio(nio)
          .backlog(backlog)
          .recvBufferSize(recv_buf_size)
          .socketReadTimeout(soTimeout)
          .lingerTimeout(soLinger)
          .emitSuspectEvents(suspects)
          .dumpMessages(dump_msgs)
          .maxLength(max_length);
        router.diagHandler().setEnabled(diag_enabled)
          .enableUdp(diag_enable_udp)
          .enableTcp(diag_enable_tcp)
          .setMcastAddress(diag_mcast_addr)
          .setBindAddress(diag_bind_addr)
          .setPort(diag_port)
          .setPortRange(diag_port_range)
          .setTtl(diag_ttl)
          .setBindInterfaces(diag_bind_interfaces)
          .setPasscode(diag_passcode);

        if (tls_protocol!=null) {
            SslContextFactory sslContextFactory = new SslContextFactory();
            sslContextFactory
                  .sslProtocol(tls_protocol)
                  .sslProvider(tls_provider)
                  .keyStoreFileName(tls_keystore_path)
                  .keyStorePassword(tls_keystore_password)
                  .keyStoreType(tls_keystore_type)
                  .keyAlias(tls_keystore_alias)
                  .trustStoreFileName(tls_truststore_path)
                  .trustStorePassword(tls_truststore_password)
                  .trustStoreType(tls_truststore_type);
            SSLContext context = sslContextFactory.getContext();
            DefaultSocketFactory socketFactory = new DefaultSocketFactory(context);
            SSLParameters serverParameters = new SSLParameters();
            socketFactory.setServerSocketConfigurator(s -> ((SSLServerSocket)s).setSSLParameters(serverParameters));
            serverParameters.setSNIMatchers(tls_sni_matchers);
            switch (tls_client_auth) {
                case NEED:
                    serverParameters.setNeedClientAuth(true);
                    break;
                case WANT:
                    serverParameters.setWantClientAuth(true);
                    break;
                default:
                    break;
            }
            router.socketFactory(socketFactory);
        }
        router.start();
        long time=System.currentTimeMillis()-start;
        IpAddress local=(IpAddress)router.localAddress();
        System.out.printf("\nGossipRouter started in %d ms listening on %s:%s%s\n",
                          time, bind_addr != null? bind_addr : "0.0.0.0",  local.getPort(),
              tls_protocol==null?"":" ("+tls_protocol+")");
    }

    public enum TLSClientAuth {
        NONE,
        WANT,
        NEED
    }

    enum DumpMessages {
        NONE,
        REGISTRATION,
        ALL;

        static DumpMessages parse(String s) {
            s = s.trim();
            if (s.isEmpty() || s.equalsIgnoreCase("false")) {
                return NONE;
            } else if (s.equalsIgnoreCase("true")) {
                return ALL;
            } else {
                return valueOf(s.toUpperCase());
            }
        }
    }

    static void help() {
        System.out.println();
        System.out.println("GossipRouter [-port <port>] [-bind_addr <address>] [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println();
        System.out.println("    -backlog <backlog>      - Max queue size of backlogged connections. Must be");
        System.out.println("                              greater than zero or the default of 1000 will be");
        System.out.println("                              used.");
        System.out.println();
        System.out.println("    -jmx <true|false>       - Expose attributes and operations via JMX.\n");
        System.out.println("    -recv_buf_size <bytes>  - Sets the receive buffer");
        System.out.println();
        System.out.println("    -solinger <msecs>       - Time for setting SO_LINGER on connections. 0");
        System.out.println("                              means do not set SO_LINGER. Must be greater than");
        System.out.println("                              or equal to zero or the default of 2000 will be");
        System.out.println("                              used.");
        System.out.println();
        System.out.println("    -sotimeout <msecs>      - Time for setting SO_TIMEOUT on connections. 0");
        System.out.println("                               means don't set SO_TIMEOUT. Must be greater than");
        System.out.println("                               or equal to zero or the default of 3000 will be");
        System.out.println("                               used.");
        System.out.println();
        System.out.println("    -expiry <msecs>         - Time for closing idle connections. 0");
        System.out.println("                              means don't expire.");
        System.out.println();
        System.out.println("    -nio <true|false>       - Whether or not to use non-blocking connections (NIO)");
        System.out.println();
        System.out.println("    -max_length <bytes>     - The max size (in bytes) of a message");
        System.out.println();
        System.out.println("    -suspect <true|false>   - Whether or not to use send SUSPECT events when a conn is closed");
        System.out.println();
        System.out.println("    -dump_msgs <option>     - Dumps messages to stdout after routing them. Options:");
        System.out.println("                              none: does not dump any messages");
        System.out.println("                              registration: dumps a message when a node is registered or unregistered to a group");
        System.out.println("                              all: dumps everything");
        System.out.println();
        System.out.println("    -tls_protocol <proto>   - The name of the TLS protocol to use, e.g. TLSv1.2.");
        System.out.println("                              Setting this requires configuring key and trust stores.");
        System.out.println();
        System.out.println("    -tls_provider <name>    - The name of the security provider to use for TLS.");
        System.out.println();
        System.out.println("    -tls_keystore_path <file> - The keystore path which contains the .");
        System.out.println();
        System.out.println("    -tls_keystore_password <password> - The key store password.");
        System.out.println();
        System.out.println("    -tls_keystore_type <type>    - The type of keystore.");
        System.out.println();
        System.out.println("    -tls_keystore_alias <alias>  - The alias of the key to use as identity for this Gossip router.");
        System.out.println();
        System.out.println("    -tls_truststore_path <file>  - The truststore path.");
        System.out.println();
        System.out.println("    -tls_truststore_password <password> - The trust store password.");
        System.out.println();
        System.out.println("    -tls_truststore_type <type>  - The truststore path.");
        System.out.println();
        System.out.println("    -tls_sni_matcher <name>      - A regular expression that servers use to match and accept SNI host names.");
        System.out.println("                                   Can be repeated multiple times.");
        System.out.println();
        System.out.println("    -tls_client_auth <mode>      - none (default), want or need. Whether to require client");
        System.out.println("                                   certificate authentication.");
        System.out.println();
    }
}
