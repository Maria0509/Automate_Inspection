package Resource;

import jade.core.Agent;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.logging.Level;
import java.util.logging.Logger;
import Libraries.IResource;
import Utilities.Constants;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.core.behaviours.ThreadedBehaviourFactory;

/**
 *
 * @author Ricardo Silva Peres <ricardo.peres@uninova.pt>
 */
public class ResourceAgent extends Agent {

    String id;
    IResource myLib;
    String description;
    String[] associatedSkills;
    String location;
    private final ThreadedBehaviourFactory tbf = new ThreadedBehaviourFactory();
    private boolean executing = false;
    private String reservedProductId = null;
    private String reservedSkill = null;
    private final Queue<ResourceJob> jobQueue = new ArrayDeque<>();
    private final Queue<PendingReservation> pendingReservations = new ArrayDeque<>();
    private long currentExecutionStartedAt = 0;
    private long currentExecutionDurationMs = 0;
    private long getWaitingTimeMs() {
        long waitTimeMs = 0;
        long now = System.currentTimeMillis();

        synchronized (this) {
            if (executing) {
                long elapsed = now - currentExecutionStartedAt;
                waitTimeMs += Math.max(0, currentExecutionDurationMs - elapsed);
            }

            for (ResourceJob job : jobQueue) {
                waitTimeMs += getEstimatedSkillTimeMs(job.skill);
            }

            for (PendingReservation reservation : pendingReservations) {
                waitTimeMs += getEstimatedSkillTimeMs(reservation.skill);
            }
        }

        return waitTimeMs;
    }

    private long getEstimatedSkillTimeMs(String skill) {
        switch (skill) {
            case "sk_pick": return 2000;
            case "sk_drop": return 2000;
            case "sk_sink": return 1000;
            case "sk_g_a": return 5000;
            case "sk_g_b": return 5000;
            case "sk_g_c": return 5000;
            case "sk_q_c": return 4000;
            default: return 3000;
        }
    }
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
            myLib = (IResource) instance;
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException ex) {
            Logger.getLogger(ResourceAgent.class.getName()).log(Level.SEVERE, null, ex);
        }

        this.location = (String) args[3];
        if ("Operator".equals(getLocalName()) && !"Source".equals(this.location)) {
            System.out.println("[" + getLocalName() + "] Normalizing location from " +
                    this.location + " to Source");
            this.location = "Source";
        }

        myLib.init(this);
        this.associatedSkills = myLib.getSkills();
        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());

            for (String skill : associatedSkills) {
                ServiceDescription sd = new ServiceDescription();
                sd.setType("resource");
                sd.setName(skill);
                dfd.addServices(sd);
            }

            DFService.register(this, dfd);
            System.out.println(getLocalName() + " registered in DF.");
        } catch (FIPAException e) {
            e.printStackTrace();
        }
        System.out.println("Resource Deployed: " + this.id + " Executes: " + Arrays.toString(associatedSkills));

        registerInDF();
        addBehaviour(new ResourceResponderBehaviour());


    }

    private void registerInDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());

        for (String skill : associatedSkills) {
            ServiceDescription sd = new ServiceDescription();
            sd.setType(Constants.DFSERVICE_RESOURCE);
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

    private boolean canExecute(String skill) {
        for (String s : associatedSkills) {
            if (s.equals(skill)) return true;
        }
        return false;
    }

    //calcular tempo estimado de transporte
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

    private boolean usesOccupancyControl() {
        return getLocalName().startsWith("GlueStation") ||
                getLocalName().startsWith("QualityControlStation");
    }

    private class ResourceResponderBehaviour extends CyclicBehaviour {
        @Override
        public void action() {
            ACLMessage msg = receive();

            if (msg == null) {
                block();
                return;
            }

            // 1) Negociação: CFP
            if (msg.getPerformative() == ACLMessage.CFP &&
                    Constants.ONTOLOGY_NEGOTIATE_RESOURCE.equals(msg.getOntology())) {

                String content = msg.getContent();
                String[] parts = content.split("#");

                if (parts.length != 3) {
                    System.out.println("[" + getLocalName() + "] Invalid CFP content: " + content);
                    return;
                }

                String productID = parts[0];
                String skill = parts[1];
                String currentLocation = parts[2];

                System.out.println("[" + getLocalName() + "] CFP received: product=" + productID +
                        " skill=" + skill + ", currentLocation=" + currentLocation);

                boolean canPropose;
                synchronized (ResourceAgent.this) {
                    canPropose = canExecute(skill) &&
                            (!usesOccupancyControl() || reservedProductId == null);
                }

                System.out.println("[" + getLocalName() + "] Proposal decision for product=" + productID +
                        " skill=" + skill + " canPropose=" + canPropose +
                        " reservedProductId=" + reservedProductId +
                        " reservedSkill=" + reservedSkill +
                        " executing=" + executing +
                        " pendingReservations=" + pendingReservations.size() +
                        " queueSize=" + jobQueue.size());

                if (canPropose) {
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.PROPOSE);

                    long travelTimeMs = getTravelTimeMs(currentLocation, location);
                    long waitingTimeMs = getWaitingTimeMs();
                    long processTimeMs = getEstimatedSkillTimeMs(skill);
                    long metric = travelTimeMs + waitingTimeMs + processTimeMs;

                    reply.setContent(String.valueOf(metric));

                    System.out.println("[" + getLocalName() + "] Sending PROPOSE with metric=" + metric +
                            "ms (travel=" + travelTimeMs + "ms, waiting=" + waitingTimeMs +
                            "ms, process=" + processTimeMs + "ms)");

                    send(reply);
                }
                if (!canPropose) {
                    ACLMessage refuse = msg.createReply();
                    refuse.setPerformative(ACLMessage.REFUSE);
                    refuse.setOntology(Constants.ONTOLOGY_NEGOTIATE_RESOURCE);
                    refuse.setConversationId(msg.getConversationId());
                    refuse.setContent("busy");
                    send(refuse);

                    System.out.println("[" + getLocalName() + "] REFUSE sent for product="
                            + productID + " skill=" + skill + " reason=busy");
                }

                return;
            }

            // 2) Recurso escolhido -> envia localização
            if (msg.getPerformative() == ACLMessage.ACCEPT_PROPOSAL &&
                    Constants.ONTOLOGY_NEGOTIATE_RESOURCE.equals(msg.getOntology())) {
                String content = msg.getContent();
                String[] parts = content.split("#");

                if (parts.length != 2) {
                    System.out.println("[" + getLocalName() + "] Invalid ACCEPT content: " + content);
                    return;
                }

                String productID = parts[0];
                String skill = parts[1];

                synchronized (ResourceAgent.this) {
                    if (usesOccupancyControl()) {
                        boolean alreadyReservedByOtherProduct =
                                reservedProductId != null &&
                                (!reservedProductId.equals(productID) || !reservedSkill.equals(skill));

                        if (alreadyReservedByOtherProduct) {
                            ACLMessage fail = msg.createReply();
                            fail.setPerformative(ACLMessage.FAILURE);
                            fail.setContent("resource_already_reserved");
                            send(fail);
                            System.out.println("[" + getLocalName() + "] Rejecting ACCEPT for product=" +
                                    productID + " skill=" + skill + " because station is reserved by " +
                                    reservedProductId);
                            return;
                        }

                        reservedProductId = productID;
                        reservedSkill = skill;
                    }

                    pendingReservations.add(new PendingReservation(productID, skill));
                    System.out.println("[" + getLocalName() + "] Reservation stored: product=" +
                            productID + " skill=" + skill +
                            " reservedProductId=" + reservedProductId +
                            " reservedSkill=" + reservedSkill +
                            " pendingReservations=" + pendingReservations.size());
                }

                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.INFORM);
                reply.setContent(location);

                System.out.println("[" + getLocalName() + "] ACCEPT_PROPOSAL received for product=" + productID +
                        " skill=" + skill + ", currentQueueWait=" + getWaitingTimeMs() +
                        "ms, sending location=" + location);
                send(reply);
                return;
            }

            if (msg.getPerformative() == ACLMessage.REJECT_PROPOSAL &&
                    Constants.ONTOLOGY_NEGOTIATE_RESOURCE.equals(msg.getOntology())) {

                String content = msg.getContent();
                String[] parts = content.split("#");

                if (parts.length != 2) {
                    System.out.println("[" + getLocalName() + "] Invalid REJECT content: " + content);
                    return;
                }

                String productID = parts[0];
                String skill = parts[1];

                synchronized (ResourceAgent.this) {
                    removePendingReservation(productID, skill);
                }

                System.out.println("[" + getLocalName() + "] REJECT_PROPOSAL received for product=" +
                        productID + " skill=" + skill);

                return;
            }

            // 3) Pedido de execução
            if (msg.getPerformative() == ACLMessage.REQUEST &&
                    Constants.ONTOLOGY_EXECUTE_SKILL.equals(msg.getOntology())) {
                String content = msg.getContent();
                String[] parts = content.split("#");

                if (parts.length != 2) {
                    ACLMessage fail = msg.createReply();
                    fail.setPerformative(ACLMessage.FAILURE);
                    fail.setContent("invalid_exec_request");
                    send(fail);
                    System.out.println("[" + getLocalName() + "] Invalid execution request: " + content);
                    return;
                }

                String productID = parts[0];
                String skill = parts[1];

                synchronized (ResourceAgent.this) {
                    if (usesOccupancyControl()) {
                        boolean validReservation =
                                productID.equals(reservedProductId) && skill.equals(reservedSkill);


                        if (!validReservation) {
                            ACLMessage fail = msg.createReply();
                            fail.setPerformative(ACLMessage.FAILURE);
                            fail.setContent("resource_not_reserved_for_product");
                            send(fail);
                            System.out.println("[" + getLocalName() + "] Refusing execution for product=" +
                                    productID + " skill=" + skill + " without matching reservation.");
                            return;
                        }
                    }

                    System.out.println("[" + getLocalName() + "] Accepting execution request: product=" +
                            productID + " skill=" + skill +
                            " reservedProductId=" + reservedProductId +
                            " reservedSkill=" + reservedSkill +
                            " pendingReservations=" + pendingReservations.size());

                    ACLMessage agree = msg.createReply();
                    agree.setPerformative(ACLMessage.AGREE);
                    send(agree);

                    removePendingReservation(productID, skill);
                    jobQueue.add(new ResourceJob(productID, skill, msg));
                    System.out.println("[" + getLocalName() + "] Job queued for product=" + productID +
                            " skill=" + skill
                            + " queueSize=" + jobQueue.size() +
                            " pendingReservations=" + pendingReservations.size());
                    if (!executing) {
                        startExecutionWorker();
                    }
                }
            }
        }
    }

    private void removePendingReservation(String productID, String skill) {
        PendingReservation match = null;

        for (PendingReservation reservation : pendingReservations) {
            if (reservation.productID.equals(productID) && reservation.skill.equals(skill)) {
                match = reservation;
                break;
            }
        }

        if (match != null) {
            pendingReservations.remove(match);
        }
    }

    private synchronized void startExecutionWorker() {
        if (executing) return;
        executing = true;

        addBehaviour(tbf.wrap(new OneShotBehaviour() {
            @Override
            public void action() {
                while (true) {
                    ResourceJob job;

                    synchronized (ResourceAgent.this) {
                        job = jobQueue.poll();
                        if (job == null) {
                            executing = false;
                            return;
                        }
                    }

                    boolean ok = false;
                    long estimatedDurationMs = getEstimatedSkillTimeMs(job.skill);

                    try {
                        synchronized (ResourceAgent.this) {
                            currentExecutionStartedAt = System.currentTimeMillis();
                            currentExecutionDurationMs = estimatedDurationMs;
                        }
                        System.out.println("[" + getLocalName() + "] Calling myLib.executeSkill(product=" +
                                job.productID + ", skill=" + job.skill +
                                ", reservedProductId=" + reservedProductId +
                                ", reservedSkill=" + reservedSkill +
                                ", estimatedDurationMs=" + estimatedDurationMs + ")");
                        ok = myLib.executeSkill(job.productID, job.skill);
                        System.out.println("[" + getLocalName() + "] executeSkill returned: " + ok);
                    } catch (Exception e) {
                        System.out.println("[" + getLocalName() + "] ERROR during executeSkill: " + e.getMessage());
                        e.printStackTrace();
                    } finally {
                        synchronized (ResourceAgent.this) {
                            currentExecutionStartedAt = 0;
                            currentExecutionDurationMs = 0;
                            if (usesOccupancyControl()) {
                                System.out.println("[" + getLocalName() + "] Releasing reservation after execution: " +
                                        "product=" + reservedProductId + " skill=" + reservedSkill);
                                reservedProductId = null;
                                reservedSkill = null;
                            }
                        }
                    }

                    ACLMessage result = job.originalMsg.createReply();
                    if (ok) {
                        result.setPerformative(ACLMessage.INFORM);
                        if (job.skill.equals("sk_q_c")) {
                            result.setContent(myLib.getLastInspectionResponse());
                        } else {
                            result.setContent("done");
                        }
                        System.out.println("[" + getLocalName() + "] Sending INFORM");
                    } else {
                        result.setPerformative(ACLMessage.FAILURE);
                        result.setContent("failed");
                        System.out.println("[" + getLocalName() + "] Sending FAILURE");
                    }
                    myAgent.send(result);
                }
            }
        }));
    }

    private static class ResourceJob {
        final String productID;
        final String skill;
        final ACLMessage originalMsg;

        ResourceJob(String productID, String skill, ACLMessage originalMsg) {
            this.productID = productID;
            this.skill = skill;
            this.originalMsg = originalMsg;
        }
    }

    private static class PendingReservation {
        final String productID;
        final String skill;

        PendingReservation(String productID, String skill) {
            this.productID = productID;
            this.skill = skill;
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
