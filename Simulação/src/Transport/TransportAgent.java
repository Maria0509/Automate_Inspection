package Transport;

import Libraries.ITransport;
import Utilities.Constants;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.behaviours.ThreadedBehaviourFactory;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.ACLMessage;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Ricardo Silva Peres <ricardo.peres@uninova.pt>
 */
public class TransportAgent extends Agent {

    String id;
    ITransport myLib;
    String description;
    String[] associatedSkills;
    private final ThreadedBehaviourFactory tbf = new ThreadedBehaviourFactory();
    private final Queue<MoveJob> moveQueue = new ArrayDeque<>();
    private boolean moving = false;
    private String currentLocation = "Source";


    @Override
    protected void setup() {
        Object[] args = this.getArguments();
        this.id = (String) args[0];
        this.description = (String) args[1];

        //Load hw lib
        try {
            String className = "Libraries." + (String) args[2];
            Class cls = Class.forName(className);
            Object instance;
            instance = cls.newInstance();
            myLib = (ITransport) instance;
            System.out.println(instance);
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException ex) {
            Logger.getLogger(TransportAgent.class.getName()).log(Level.SEVERE, null, ex);
        }

        myLib.init(this);
        this.associatedSkills = myLib.getSkills();
        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());

            for (String skill : associatedSkills) {
                ServiceDescription sd = new ServiceDescription();
                sd.setType("transport");
                sd.setName(skill);
                dfd.addServices(sd);
            }

            DFService.register(this, dfd);
            System.out.println(getLocalName() + " registered in DF.");
        } catch (FIPAException e) {
            e.printStackTrace();
        }
        System.out.println("Transport Deployed: " + this.id + " Executes: " + Arrays.toString(associatedSkills));
        addBehaviour(new TransportResponderBehaviour());

        registerInDF();

        // TO DO: Add responder behaviour/s
    }

    private void registerInDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());

        for (String skill : associatedSkills) {
            ServiceDescription sd = new ServiceDescription();
            sd.setType(Constants.DFSERVICE_TRANSPORT);
            sd.setName(skill);
            dfd.addServices(sd);
        }

        try {
            DFService.register(this, dfd);
            System.out.println(getLocalName() + " registered in DF.");
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }

    private class TransportResponderBehaviour extends CyclicBehaviour {
        @Override
        public void action() {
            ACLMessage msg = receive();

            if (msg == null) {
                block();
                return;
            }
            System.out.println("[AGV] Message received: performative=" + msg.getPerformative()
                    + " ontology=" + msg.getOntology()
                    + " content=" + msg.getContent());

            if (msg.getPerformative() == ACLMessage.REQUEST &&
                    Constants.ONTOLOGY_MOVE.equals(msg.getOntology())) {

                String content = msg.getContent();
                String[] parts = content.split("#");
                System.out.println("[AGV] Parsed parts length = " + parts.length);

                if (parts.length != 3) {
                    ACLMessage fail = msg.createReply();
                    fail.setPerformative(ACLMessage.FAILURE);
                    fail.setContent("invalid_move_request");
                    send(fail);
                    System.out.println("[AGV] Invalid move request.");
                    return;
                }

                String productID = parts[0];
                String origin = parts[1];
                String destination = parts[2];

                System.out.println("[AGV] Move request for product " + productID +
                        " from " + origin + " to " + destination);

                ACLMessage agree = msg.createReply();
                agree.setPerformative(ACLMessage.AGREE);
                agree.setOntology(Constants.ONTOLOGY_MOVE);
                send(agree);

                System.out.println("[AGV] AGREE sent.");

                synchronized (TransportAgent.this) {
                    moveQueue.add(new MoveJob(productID, origin, destination, msg));
                    System.out.println("[AGV] Job queued: " + productID + " " + origin + " -> " + destination
                            + " queueSize=" + moveQueue.size());
                    if (!moving) {
                        startMoveWorker();
                    }
                }
            }
        }
    }

    private synchronized void startMoveWorker() {
        if (moving) return;
        moving = true;

        addBehaviour(tbf.wrap(new OneShotBehaviour() {
            @Override
            public void action() {
                while (true) {
                    MoveJob job;

                    synchronized (TransportAgent.this) {
                        job = selectNextJob();
                        if (job == null) {
                            moving = false;
                            return;
                        }
                    }

                    System.out.println("[AGV] Current location: " + currentLocation);
                    System.out.println("[AGV] Starting queued move: " + job.productID +
                            " " + job.origin + " -> " + job.destination);
                    boolean ok = myLib.executeMove(job.origin, job.destination, job.productID);
                    System.out.println("[AGV] executeMove returned: " + ok);

                    if (ok) {
                        currentLocation = job.destination;

                        try {
                            Thread.sleep(1000); // tempo para estabilização física
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    ACLMessage result = job.originalMsg.createReply();
                    if (ok) {
                        result.setPerformative(ACLMessage.INFORM);
                        result.setContent("moved");
                        System.out.println("[AGV] INFORM sent.");
                    } else {
                        result.setPerformative(ACLMessage.FAILURE);
                        result.setContent("move_failed");
                        System.out.println("[AGV] FAILURE sent.");
                    }
                    myAgent.send(result);
                }
            }
        }));
    }

    private MoveJob selectNextJob() {
        MoveJob best = null;
        long bestCost = Long.MAX_VALUE;

        for (MoveJob job : moveQueue) {
            long repositionCost = getTravelTimeMs(currentLocation, job.origin);
            long transportCost = getTravelTimeMs(job.origin, job.destination);
            long totalCost = repositionCost + transportCost;

            if (best == null || totalCost < bestCost) {
                best = job;
                bestCost = totalCost;
            }
        }

        if (best != null) {
            moveQueue.remove(best);
            System.out.println("[AGV] Selected next job with estimated cost=" + bestCost +
                    " from currentLocation=" + currentLocation);
        }

        return best;
    }

    private long getTravelTimeMs(String from, String to) {
        if (from.equals(to)) return 0;

        if (from.equals("Source") && to.equals("GlueStation1")) return 1000;
        if (from.equals("Source") && to.equals("GlueStation2")) return 2000;
        if (from.equals("Source") && to.equals("QualityControlStation1")) return 3000;
        if (from.equals("Source") && to.equals("QualityControlStation2")) return 4000;

        if (from.equals("GlueStation1") && to.equals("Source")) return 1000;
        if (from.equals("GlueStation1") && to.equals("GlueStation2")) return 2000;
        if (from.equals("GlueStation1") && to.equals("QualityControlStation1")) return 2000;
        if (from.equals("GlueStation1") && to.equals("QualityControlStation2")) return 3000;

        if (from.equals("GlueStation2") && to.equals("Source")) return 2000;
        if (from.equals("GlueStation2") && to.equals("GlueStation1")) return 2000;
        if (from.equals("GlueStation2") && to.equals("QualityControlStation1")) return 3000;
        if (from.equals("GlueStation2") && to.equals("QualityControlStation2")) return 2000;

        if (from.equals("QualityControlStation1") && to.equals("Source")) return 3000;
        if (from.equals("QualityControlStation1") && to.equals("GlueStation1")) return 2000;
        if (from.equals("QualityControlStation1") && to.equals("GlueStation2")) return 3000;
        if (from.equals("QualityControlStation1") && to.equals("QualityControlStation2")) return 2000;

        if (from.equals("QualityControlStation2") && to.equals("Source")) return 4000;
        if (from.equals("QualityControlStation2") && to.equals("GlueStation1")) return 3000;
        if (from.equals("QualityControlStation2") && to.equals("GlueStation2")) return 2000;
        if (from.equals("QualityControlStation2") && to.equals("QualityControlStation1")) return 2000;

        return 999999;
    }

    private static class MoveJob {
        final String productID;
        final String origin;
        final String destination;
        final ACLMessage originalMsg;

        MoveJob(String productID, String origin, String destination, ACLMessage originalMsg) {
            this.productID = productID;
            this.origin = origin;
            this.destination = destination;
            this.originalMsg = originalMsg;
        }
    }


    @Override
    protected void takeDown() {
        try {
            tbf.interrupt();
            DFService.deregister(this);
        } catch (Exception e) {
            e.printStackTrace();
        }
        super.takeDown();
    }

}
