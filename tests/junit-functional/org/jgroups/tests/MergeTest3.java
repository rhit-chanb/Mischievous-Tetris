package org.jgroups.tests;

import org.jgroups.*;
import org.jgroups.jmx.JmxConfigurator;
import org.jgroups.protocols.DISCARD;
import org.jgroups.protocols.SHARED_LOOPBACK;
import org.jgroups.protocols.SHARED_LOOPBACK_PING;
import org.jgroups.protocols.UNICAST3;
import org.jgroups.protocols.pbcast.GMS;
import org.jgroups.protocols.pbcast.NAKACK2;
import org.jgroups.protocols.pbcast.STABLE;
import org.jgroups.util.MergeId;
import org.jgroups.util.MutableDigest;
import org.jgroups.util.Util;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * Tests a merge between partitions {A,B,C} and {D,E,F} merge, but 1 member in each partition is already involved
 * in a different merge (GMS.merge_id != null). For example, if C and E are busy with a different merge, the MergeView
 * should exclude them: {A,B,D,F}. The digests must also exclude C and E.
 * @author Bela Ban
 */
@Test(groups=Global.FUNCTIONAL,singleThreaded=true)
public class MergeTest3 {
    protected JChannel a,b,c,d,e,f;
    protected JChannel[] channels;


    @BeforeMethod
    void setUp() throws Exception {
        a=createChannel("A");
        b=createChannel("B");
        c=createChannel("C");
        d=createChannel("D");
        e=createChannel("E");
        f=createChannel("F");
        channels=new JChannel[]{a,b,c,d,e,f};
    }


    @AfterMethod
    void tearDown() throws Exception {
        Stream.of(channels).forEach(Util::close);
    }


    public void testMergeWithMissingMergeResponse() throws TimeoutException {
        createPartition(a,b,c);
        createPartition(d,e,f);

        System.out.println("Views are:");
        for(JChannel ch: Arrays.asList(a,b,c,d,e,f))
            System.out.println(ch.getAddress() + ": " + ch.getView());

        JChannel merge_leader=findMergeLeader(a,b,c,d,e,f);
        List<Address> first_partition=getMembers(a,b,c);
        List<Address> second_partition=getMembers(d,e,f);

        Collections.sort(first_partition);
        Address first_coord=first_partition.remove(0); // remove the coord
        Address busy_first=first_partition.get(0);

        Collections.sort(second_partition);
        Address second_coord=second_partition.remove(0);
        Address busy_second=second_partition.get(second_partition.size() -1);

        System.out.println("\nMerge leader: " + merge_leader.getAddress() + "\nBusy members: " + Arrays.asList(busy_first, busy_second));

        MergeId busy_merge_id=MergeId.create(a.getAddress());
        setMergeIdIn(busy_first, busy_merge_id);
        setMergeIdIn(busy_second, busy_merge_id);
        for(JChannel ch: channels) { // excluding faulty member, as it still discards messages
            assert ch.getView().size() == 3;
            GMS gms=ch.getProtocolStack().findProtocol(GMS.class);
            gms.setJoinTimeout(3000);
            DISCARD discard=ch.getProtocolStack().findProtocol(DISCARD.class);
            discard.discardAll(false);
        }

        System.out.println("Injecting MERGE event into merge leader " + merge_leader.getAddress());
        GMS gms=merge_leader.getProtocolStack().findProtocol(GMS.class);
        int i=10;
        do {
            Map<Address,View> merge_views=new HashMap<>(6);
            merge_views.put(first_coord, findChannel(first_coord).getView());
            merge_views.put(second_coord, findChannel(second_coord).getView());
            gms.up(new Event(Event.MERGE, merge_views));
            boolean done=true;
            System.out.println();
            for(JChannel ch : channels) {
                System.out.println("==> " + ch.getAddress() + ": " + ch.getView());
                Address addr=ch.getAddress();
                if(addr.equals(busy_first) || addr.equals(busy_second)) {
                    if(ch.getView().size() != 3)
                        done=false;
                }
                else if(ch.getView().size() != 4)
                    done=false;
            }
            if(done)
                break;
            Util.sleep(2000);
        }
        while(--i >= 0);

        for(JChannel ch: channels) {
            if(ch.getAddress().equals(busy_first) || ch.getAddress().equals(busy_second))
                assert ch.getView().size() == 3;
            else
                assert ch.getView().size() == 4 : ch.getAddress() + "'s view: " + ch.getView();
        }

        System.out.println("\n************************ Now merging the entire cluster ****************");
        cancelMerge(busy_first);
        cancelMerge(busy_second);

        System.out.println("Injecting MERGE event into merge leader " + merge_leader.getAddress());
        Map<Address,View> merge_views=new HashMap<>(6);
        i=10;
        do {
            merge_views=new HashMap<>(6);
            merge_views.put(merge_leader.getAddress(), merge_leader.getView());
            merge_views.put(busy_first, findChannel(busy_first).getView());
            merge_views.put(busy_second, findChannel(busy_second).getView());
            gms.up(new Event(Event.MERGE, merge_views));
            if(Stream.of(channels).allMatch(c -> c.getView().size() == 6))
                break;
            Util.sleep(2000);
        }
        while(--i >= 0);

        System.out.printf("channels:\n%s\n",
                          Stream.of(channels).map(c -> String.format("%s: %s", c.getAddress(), c.getView()))
                            .collect(Collectors.joining("\n")));
        assert Stream.of(channels).allMatch(c -> c.getView().size() == channels.length);
    }

    protected static JChannel createChannel(String name) throws Exception {
        JChannel retval=new JChannel(new SHARED_LOOPBACK(),
                                     new DISCARD().discardAll(true),
                                     new SHARED_LOOPBACK_PING(),
                                     new NAKACK2().useMcastXmit(false)
                                       .logDiscardMessages(false).logNotFoundMessages(false),
                                     new UNICAST3(),
                                     new STABLE().setMaxBytes(50000),
                                     new GMS().printLocalAddress(false).setJoinTimeout( 1).setLeaveTimeout(100)
                                       .setMergeTimeout(5000).logViewWarnings(false).setViewAckCollectionTimeout(50)
                                       .logCollectMessages(false))
          .name(name);
        retval.connect("MergeTest3");
        JmxConfigurator.registerChannel(retval, Util.getMBeanServer(), name, retval.getClusterName(), true);
        return retval;
    }

    protected void setMergeIdIn(Address mbr, MergeId busy_merge_id) {
        GMS gms=findChannel(mbr).getProtocolStack().findProtocol(GMS.class);
        gms.getMerger().setMergeId(null, busy_merge_id);
    }

    protected void cancelMerge(Address mbr) {
        GMS gms=findChannel(mbr).getProtocolStack().findProtocol(GMS.class);
        gms.cancelMerge();
    }

    protected JChannel findChannel(Address mbr) {
        for(JChannel ch: Arrays.asList(a,b,c,d,e,f)) {
            if(ch.getAddress().equals(mbr))
                return ch;
        }
        return null;
    }

    protected static void createPartition(JChannel... channels) {
        long view_id=1; // find the highest view-id +1
        for(JChannel ch: channels)
            view_id=Math.max(ch.getView().getViewId().getId(), view_id);
        view_id++;

        List<Address> members=getMembers(channels);
        Collections.sort(members);
        Address coord=members.get(0);
        View view=new View(coord, view_id, members);
        MutableDigest digest=new MutableDigest(view.getMembersRaw());
        for(JChannel ch: channels) {
            NAKACK2 nakack=ch.getProtocolStack().findProtocol(NAKACK2.class);
            digest.merge(nakack.getDigest(ch.getAddress()));
        }
        for(JChannel ch: channels) {
            GMS gms=ch.getProtocolStack().findProtocol(GMS.class);
            gms.installView(view, digest);
        }
    }

    protected static List<Address> getMembers(JChannel... channels) {
        List<Address> members=new ArrayList<>(channels.length);
        for(JChannel ch: channels)
            members.add(ch.getAddress());
        return members;
    }

    protected static Address determineCoordinator(JChannel... channels) {
        List<Address> list=new ArrayList<>(channels.length);
        for(JChannel ch: channels)
            list.add(ch.getAddress());
        Collections.sort(list);
        return list.get(0);
    }

    protected static JChannel findMergeLeader(JChannel... channels) {
        Set<Address> tmp=new TreeSet<>();
        for(JChannel ch: channels)
            tmp.add(ch.getAddress());
        Address leader=tmp.iterator().next();
        for(JChannel ch: channels)
            if(ch.getAddress().equals(leader))
                return ch;
        return null;
    }



    @Test(enabled=false)
    public static void main(String[] args) throws Exception {
        MergeTest3 test=new MergeTest3();
        test.setUp();
        test.testMergeWithMissingMergeResponse();
        test.tearDown();
    }


  
}
