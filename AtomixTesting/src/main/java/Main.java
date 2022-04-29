import io.atomix.cluster.Node;
import io.atomix.cluster.discovery.BootstrapDiscoveryProvider;
import io.atomix.core.Atomix;
import io.atomix.core.profile.Profile;
import io.atomix.core.value.AtomicValue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class Main {

    private static Map<String, MemberData> members;

    private static MemberData me;

    public static void main(String[] args) {

        if (args.length < 1) {
            System.out.println("Should be 1 args: myName");
            System.out.println("Ex: member1");
            return;
        }
        setUpMembers();
        String myName = args[0];
        me = members.get(myName);
        if (me == null) {
            System.out.println("No member with name " + myName + " is defined");
            System.exit(1);
        }
        System.out.println("I am " + me.name + " on host " + me.hostname + ":" + me.port);

        try2();

        System.out.println("Try2 done");

    }

    private static void setUpMembers() {
        members = new HashMap<>();
        MemberData member1 = new MemberData("member1", "localhost", 25000);
        MemberData member2 = new MemberData("member2", "localhost", 25100);
        members.put(member1.name, member1);
        members.put(member2.name, member2);
    }

    private static void try2() {
        Node member1 = members.get("member1").toNode();
        Node member2 = members.get("member2").toNode();

        Atomix atomix = Atomix.builder()
                .withMemberId(me.name)
                .withHost(me.hostname)
                .withPort(me.port)
//                .withMulticastEnabled()
                .withMembershipProvider(BootstrapDiscoveryProvider.builder()
                        .withNodes(member1, member2)
                        .build())
                .withProfiles(Profile.client())
                .build();

        System.out.println("Trying to start...");
        CompletableFuture<Void> future = atomix.start();
        System.out.println("Trying to join...");
        future.join();
        System.out.println("Joined");

        AtomicValue<String> value = atomix.getAtomicValue("value");

        while(true) {
            if (me.name.equals("member1")) {
                value.set("Other Value!!!");
            }
            System.out.println("" + me.name + ": " + "Atomic value is " + value.get());
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private static class MemberData {
        final String name;
        final String hostname;
        final int port;

        public MemberData(String name, String hostname, int port) {
            this.name = name;
            this.hostname = hostname;
            this.port = port;
        }

        public Node toNode() {
            return Node.builder()
                    .withId(name)
                    .withHost(hostname)
                    .withPort(port)
                    .build();
        }
    }


//    private void try1() {
//        Atomix atomix = Atomix.builder()
//                .withMemberId("member1")
//
//                .withNodeDiscovery(BootstrapDiscoveryProvider.builder()
//                        .withNodes(
//                                Node.builder()
//                                        .withId("member1")
//                                        .withHost("localhost")
//                                        .withPort(5600)
////                                        .withAddress("10.192.19.181:5679")
//                                        .build(),
//                                Node.builder()
//                                        .withId("member2")
//                                        .withHost("localhost")
//                                        .withPort(5600)
////                                        .withAddress("10.192.19.182:5679")
//                                        .build())
//                        .build())
////                .withManagementGroup(RaftPartitionGroup.builder("system")
////                        .withNumPartitions(1)
////                        .withMembers("member1", "member2", "member3")
////                        .build())
////                .withPartitionGroups(RaftPartitionGroup.builder("raft")
////                        .withPartitionSize(3)
////                        .withNumPartitions(3)
////                        .withMembers("member1", "member2", "member3")
////                        .build())
//                .build();
//
//    }
}
