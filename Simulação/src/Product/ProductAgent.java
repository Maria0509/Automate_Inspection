package Product;

import Utilities.Constants;
import jade.core.AID;
import jade.core.Agent;
import java.util.ArrayList;
import jade.core.behaviours.OneShotBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
/**
 *
 * @author Ricardo Silva Peres <ricardo.peres@uninova.pt>
 */
public class ProductAgent extends Agent {
    String id;
    ArrayList<String> executionPlan = new ArrayList<>();
    String currentLocation="pad_operator";
    int currentStep = 0;

    private boolean recoveryAlreadyAttempted = false;
    String currentSkill;
    jade.core.AID selectedResource;
    String selectedResourceLocation;
    boolean waitingForTransport = false;
    boolean waitingForExecution = false;
    String orderAgentName;
    // TO DO: Add remaining attributes required for your implementation

    @Override
    protected void setup() {
        Object[] args = this.getArguments();
        this.id = (String) args[0];
        this.executionPlan = this.getExecutionList((String) args[1]);
        this.currentLocation="Source";
        this.orderAgentName = (String) args[2];
        System.out.println("Product launched: " + this.id + " Requires: " + executionPlan);
        addBehaviour(new ProductControlBehaviour());
        
    }

    @Override
    protected void takeDown() {
        super.takeDown(); //To change body of generated methods, choose Tools | Templates.
    }
    
    private ArrayList<String> getExecutionList(String productType){
        switch(productType){
            case "A": return new ArrayList<>(Utilities.Constants.PROD_A);
            case "B": return new ArrayList<>(Utilities.Constants.PROD_B);
            case "C": return new ArrayList<>(Utilities.Constants.PROD_C);
        }
        return null;
    }

    private class ProductControlBehaviour extends OneShotBehaviour {

        @Override
        public void action() {
            while (currentStep < executionPlan.size()) {
                String currentSkill = executionPlan.get(currentStep);
                System.out.println("\n[" + id + "] Starting step " + currentStep + ": " + currentSkill);

                ProposalData reservedProposal = null;
                int attempt = 1;

                while (reservedProposal == null) {

                    System.out.println("[" + id + "] Attempt " + attempt + " to reserve resource for " + currentSkill);

                    AID[] candidateResources = searchDF(Constants.DFSERVICE_RESOURCE, currentSkill);

                    if (candidateResources.length == 0) {
                        System.out.println("[" + id + "] No resource found for skill: " + currentSkill + ". Waiting...");
                        doWait(1000);
                        attempt++;
                        continue;
                    }

                    ArrayList<ProposalData> proposals = negotiateResource(currentSkill, candidateResources);

                    if (proposals != null && !proposals.isEmpty()) {
                        reservedProposal = reserveBestAvailableProposal(currentSkill, proposals);
                    }

                    if (reservedProposal == null) {
                        System.out.println("[" + id + "] No resource available now for skill " + currentSkill + ". Waiting...");
                        doWait(1000);
                        attempt++;
                    }
                }

                AID selectedResource = reservedProposal.agent;
                String resourceLocation = reservedProposal.knownLocation;

                System.out.println("[" + id + "] Selected resource: " + selectedResource.getLocalName()
                        + " with metric " + reservedProposal.metric);

                // 4) se necessário, pedir transporte
                if (!currentLocation.equals(resourceLocation)) {
                    boolean moved = requestTransport(currentLocation, resourceLocation);

                    if (!moved) {
                        System.out.println("[" + id + "] ABORTING PRODUCT: transport failed at step "
                                + currentStep + " skill=" + currentSkill + " currentLocation=" + currentLocation);
                        return;
                    }

                    currentLocation = resourceLocation;
                    System.out.println("[" + id + "] New location: " + currentLocation);
                    doWait(1000);
                }


                System.out.println("[" + id + "] REQUESTING EXECUTION skill=" + currentSkill
                        + " expectedResource=" + selectedResource.getLocalName()
                        + " currentLocation=" + currentLocation);
                // 5) pedir execução da skill
                boolean executed = requestSkillExecution(selectedResource, currentSkill);

                if (!executed) {
                    System.out.println("[" + id + "] ABORTING PRODUCT: skill execution failed at step "
                            + currentStep + " skill=" + currentSkill + " currentLocation=" + currentLocation);
                    return;
                }

                System.out.println("[" + id + "] Skill finished: " + currentSkill);

                if (currentSkill.equals(Constants.SK_PICK_UP)) {

                    // avisar OA
                    ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                    msg.addReceiver(new AID(orderAgentName, AID.ISLOCALNAME));
                    msg.setOntology("ont_ready_at_source");
                    msg.setContent(getLocalName());
                    send(msg);

                    System.out.println("[" + id + "] Waiting after pick...");

                    // esperar libertação
                    MessageTemplate mt = MessageTemplate.and(
                            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                            MessageTemplate.MatchOntology("ont_release_transport")
                    );

                    ACLMessage release = blockingReceive(mt);

                    System.out.println("[" + id + "] Released by OA.");
                }

                System.out.println("[" + id + "] COMPLETED STEP " + currentStep + " skill=" + currentSkill);
                currentStep++;
            }

            System.out.println("\n[" + id + "] Product finished all operations!");

// voltar ao Source
            if (!currentLocation.equals("Source")) {
                boolean movedBack = requestTransport(currentLocation, "Source");

                if (!movedBack) {
                    System.out.println("[" + id + "] ABORTING PRODUCT: failed to return to Source after all operations"
                            + " currentStep=" + currentStep + " currentLocation=" + currentLocation);
                    return;
                }

                currentLocation = "Source";
                System.out.println("[" + id + "] Returned to Source.");
            }

// pedir sink ao Operator
            AID[] sinkResources = searchDF(Constants.DFSERVICE_RESOURCE, Constants.SK_SINK);

            if (sinkResources.length == 0) {
                System.out.println("[" + id + "] ABORTING PRODUCT: no sink resource found"
                        + " currentStep=" + currentStep + " currentLocation=" + currentLocation);
                return;
            }

            ArrayList<ProposalData> sinkProposals = negotiateResource(Constants.SK_SINK, sinkResources);

            if (sinkProposals == null || sinkProposals.isEmpty()) {
                System.out.println("[" + id + "] ABORTING PRODUCT: no valid sink proposal received"
                        + " currentStep=" + currentStep + " currentLocation=" + currentLocation);
                return;
            }

            ProposalData reservedSink = reserveBestAvailableProposal(Constants.SK_SINK, sinkProposals);

            if (reservedSink == null) {
                System.out.println("[" + id + "] ABORTING PRODUCT: failed to reserve sink"
                        + " currentStep=" + currentStep + " currentLocation=" + currentLocation);
                return;
            }

            AID selectedSink = reservedSink.agent;
            String sinkLocation = reservedSink.knownLocation;

            if (sinkLocation == null) {
                System.out.println("[" + id + "] ABORTING PRODUCT: sink location is null"
                        + " currentStep=" + currentStep + " currentLocation=" + currentLocation);
                return;
            }


// executar sink
            boolean sinked = requestSkillExecution(selectedSink, Constants.SK_SINK);

            if (!sinked) {
                System.out.println("[" + id + "] ABORTING PRODUCT: sink execution failed"
                        + " currentStep=" + currentStep + " currentLocation=" + currentLocation);
                return;
            }

            ACLMessage done = new ACLMessage(ACLMessage.INFORM);
            done.addReceiver(new AID(orderAgentName, AID.ISLOCALNAME));
            done.setOntology(Constants.ONTOLOGY_PRODUCT_DONE);
            done.setContent(getLocalName());
            send(done);

            System.out.println("[" + id + "] Product left the system.");
            doDelete();
        }
    }

    private AID[] searchDF(String serviceType, String serviceName) {
        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType(serviceType);
            sd.setName(serviceName);
            template.addServices(sd);

            DFAgentDescription[] result = DFService.search(this, template);
            System.out.println("[" + id + "] Found " + result.length +
                    " agents for service: " + serviceName);
            AID[] agents = new AID[result.length];

            for (int i = 0; i < result.length; i++) {
                agents[i] = result[i].getName();
            }

            return agents;

        } catch (FIPAException e) {
            e.printStackTrace();
        }
        return new AID[0];
    }

    private ArrayList<ProposalData> negotiateResource(String skill, AID[] candidates) {
        ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
        String conversationId = "neg-res-" + id + "-" + currentStep + "-" + System.currentTimeMillis();
        cfp.setOntology(Constants.ONTOLOGY_NEGOTIATE_RESOURCE);
        cfp.setConversationId(conversationId);
        cfp.setContent(id + "#" + skill + "#" + currentLocation);

        for (AID aid : candidates) {
            cfp.addReceiver(aid);
        }

        send(cfp);
        System.out.println("[" + id + "] CFP sent for skill: " + skill);

        ArrayList<ProposalData> proposals = new ArrayList<>();

        for (int i = 0; i < candidates.length; i++) {
            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.or(
                            MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                            MessageTemplate.MatchPerformative(ACLMessage.REFUSE)
                    ),
                    MessageTemplate.and(
                            MessageTemplate.MatchOntology(Constants.ONTOLOGY_NEGOTIATE_RESOURCE),
                            MessageTemplate.MatchConversationId(conversationId)
                    )
            );

            ACLMessage reply = blockingReceive(mt);

            if (reply != null) {
                if (reply.getPerformative() == ACLMessage.PROPOSE) {
                    try {
                        double currentStepCost = Double.parseDouble(reply.getContent());
                        double totalEstimatedCost = estimateRemainingPlanCost(
                                reply.getSender(),
                                currentStep,
                                currentLocation,
                                currentStepCost
                        );

                        String knownLocation = getKnownResourceLocation(reply.getSender());

                        System.out.println("[" + id + "] Proposal from " + reply.getSender().getLocalName()
                                + " metric=" + currentStepCost
                                + " totalEstimatedCost=" + totalEstimatedCost);

                        if (knownLocation != null && totalEstimatedCost != Double.MAX_VALUE) {
                            proposals.add(new ProposalData(
                                    reply.getSender(),
                                    currentStepCost,
                                    totalEstimatedCost,
                                    knownLocation
                            ));
                        }

                    } catch (NumberFormatException e) {
                        System.out.println("[" + id + "] Invalid proposal metric from "
                                + reply.getSender().getLocalName());
                    }
                } else if (reply.getPerformative() == ACLMessage.REFUSE) {
                    System.out.println("[" + id + "] Resource refused CFP: "
                            + reply.getSender().getLocalName()
                            + " reason=" + reply.getContent());
                }
            }
        }

        proposals.sort((a, b) -> Double.compare(a.totalEstimatedCost, b.totalEstimatedCost));
        return proposals;
    }

    private void rejectUnusedProposals(String skill, ArrayList<ProposalData> proposals, AID selectedAgent) {
        for (ProposalData proposal : proposals) {
            if (!proposal.agent.equals(selectedAgent)) {
                ACLMessage reject = new ACLMessage(ACLMessage.REJECT_PROPOSAL);
                reject.addReceiver(proposal.agent);
                reject.setOntology(Constants.ONTOLOGY_NEGOTIATE_RESOURCE);
                reject.setContent(id + "#" + skill);
                send(reject);

                System.out.println("[" + id + "] Rejected unused proposal from "
                        + proposal.agent.getLocalName());
            }
        }
    }

    private ProposalData reserveBestAvailableProposal(String skill, ArrayList<ProposalData> proposals) {
        for (ProposalData proposal : proposals) {
            String conversationId = "acc-res-" + id + "-" + currentStep + "-" + System.currentTimeMillis();

            System.out.println("[" + id + "] Accepting proposal for skill " + skill
                    + " with conversationId=" + conversationId
                    + " selected=" + proposal.agent.getLocalName());

            ACLMessage accept = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
            accept.addReceiver(proposal.agent);
            accept.setOntology(Constants.ONTOLOGY_NEGOTIATE_RESOURCE);
            accept.setConversationId(conversationId);
            accept.setContent(id + "#" + skill);
            send(accept);

            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.or(
                            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                            MessageTemplate.MatchPerformative(ACLMessage.FAILURE)
                    ),
                    MessageTemplate.and(
                            MessageTemplate.MatchOntology(Constants.ONTOLOGY_NEGOTIATE_RESOURCE),
                            MessageTemplate.and(
                                    MessageTemplate.MatchConversationId(conversationId),
                                    MessageTemplate.MatchSender(proposal.agent)
                            )
                    )
            );

            ACLMessage locationMsg = blockingReceive(
                    mt,
                    Constants.SIM_RESOURCE_TIMEOUT_MS + Constants.PRODUCT_TIMEOUT_MARGIN_MS
            );

            if (locationMsg != null && locationMsg.getPerformative() == ACLMessage.INFORM) {
                System.out.println("[" + id + "] Resource confirmed reservation: sender="
                        + locationMsg.getSender().getLocalName()
                        + " location=" + locationMsg.getContent()
                        + " conversationId=" + conversationId);

                proposal.knownLocation = locationMsg.getContent();
                rejectUnusedProposals(skill, proposals, proposal.agent);
                return proposal;
            }

            if (locationMsg != null) {
                System.out.println("[" + id + "] Resource rejected reservation: sender="
                        + locationMsg.getSender().getLocalName()
                        + " performative=" + locationMsg.getPerformative()
                        + " content=" + locationMsg.getContent()
                        + " conversationId=" + conversationId);
            } else {
                System.out.println("[" + id + "] No reservation response received for conversationId="
                        + conversationId);
            }

            System.out.println("[" + id + "] Trying next candidate for skill " + skill + "...");
        }

        return null;
    }

    private boolean requestTransport(String origin, String destination) {
        AID[] transports = searchDF(Constants.DFSERVICE_TRANSPORT, Constants.SK_MOVE);

        if (transports.length == 0) {
            System.out.println("[" + id + "] No transport agent found.");
            return false;
        }

        AID transport = transports[0];
        String conversationId = "move-" + id + "-" + currentStep + "-" + System.currentTimeMillis();

        ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
        req.addReceiver(transport);
        req.setOntology(Constants.ONTOLOGY_MOVE);
        req.setConversationId(conversationId);
        req.setContent(id + "#" + origin + "#" + destination);
        send(req);

        System.out.println("[" + id + "] Transport requested to " + transport.getLocalName() +
                " | " + origin + " -> " + destination +
                " conversationId=" + conversationId);

        MessageTemplate agreeMt = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.AGREE),
                MessageTemplate.and(
                        MessageTemplate.MatchOntology(Constants.ONTOLOGY_MOVE),
                        MessageTemplate.MatchConversationId(conversationId)
                )
        );

        ACLMessage agree = blockingReceive(agreeMt, 10000);
        if (agree == null) {
            System.out.println("[" + id + "] Timeout waiting transport AGREE from " +
                    transport.getLocalName() + " conversationId=" + conversationId);
            return false;
        }

        System.out.println("[" + id + "] AGREE received for transport conversationId=" +
                conversationId + " from " + agree.getSender().getLocalName());

        MessageTemplate resultMt = MessageTemplate.and(
                MessageTemplate.or(
                        MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                        MessageTemplate.MatchPerformative(ACLMessage.FAILURE)
                ),
                MessageTemplate.and(
                        MessageTemplate.MatchOntology(Constants.ONTOLOGY_MOVE),
                        MessageTemplate.MatchConversationId(conversationId)
                )
        );

        ACLMessage result = blockingReceive(resultMt, getTransportResultTimeoutMs());
        if (result == null) {
            System.out.println("[" + id + "] Timeout waiting transport result for conversationId=" +
                    conversationId);
            return false;
        }
        System.out.println("[" + id + "] Transport result: performative=" +
                result.getPerformative() + " content=" + result.getContent() +
                " conversationId=" + conversationId);
        return result.getPerformative() == ACLMessage.INFORM;
    }

    private boolean requestSkillExecution(AID resource, String skill) {
        String conversationId = "exec-" + id + "-" + currentStep + "-" + System.currentTimeMillis();
        ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
        req.addReceiver(resource);
        req.setOntology(Constants.ONTOLOGY_EXECUTE_SKILL);
        req.setConversationId(conversationId);
        req.setContent(id + '#' + skill);
        send(req);

        System.out.println("[" + id + "] Execution requested to " + resource.getLocalName() +
                " for skill " + skill + " conversationId=" + conversationId +
                " currentLocation=" + currentLocation);

        MessageTemplate agreeMt = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.AGREE),
                MessageTemplate.and(
                        MessageTemplate.MatchOntology(Constants.ONTOLOGY_EXECUTE_SKILL),
                        MessageTemplate.MatchConversationId(conversationId)
                )
        );

        ACLMessage agree = blockingReceive(agreeMt, 10000);
        if (agree == null) {
            System.out.println("[" + id + "] Timeout waiting execution AGREE from " +
                    resource.getLocalName() + " conversationId=" + conversationId);
            return false;
        }

        System.out.println("[" + id + "] AGREE received for execution conversationId=" +
                conversationId + " from " + agree.getSender().getLocalName());

        MessageTemplate resultMt = MessageTemplate.and(
                MessageTemplate.or(
                        MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                        MessageTemplate.MatchPerformative(ACLMessage.FAILURE)
                ),
                MessageTemplate.and(
                        MessageTemplate.MatchOntology(Constants.ONTOLOGY_EXECUTE_SKILL),
                        MessageTemplate.MatchConversationId(conversationId)
                )
        );

        ACLMessage result = blockingReceive(resultMt, getExecutionResultTimeoutMs(skill));

        if (result == null) {
            System.out.println("[" + id + "] Timeout waiting execution result from " +
                    resource.getLocalName() + " for skill " + skill);
            return false;
        }

        System.out.println("[" + id + "] Execution result: performative=" +
                result.getPerformative() + " content=" + result.getContent() +
                " conversationId=" + conversationId);

        String resultContent = result.getContent();

        if (Constants.SK_QUALITY_CHECK.equals(skill) &&
                result.getPerformative() == ACLMessage.INFORM &&
                resultContent != null) {

            if ((resultContent.contains("\"status\":\"NOK\"") || resultContent.contains("\"status\": \"NOK\""))
                    && !recoveryAlreadyAttempted) {

                recoveryAlreadyAttempted = true;

                System.out.println("[" + getLocalName() + "] NOK detected. Adding one recovery attempt.");

                executionPlan.add(currentStep + 1, Constants.SK_GLUE_TYPE_A);
                executionPlan.add(currentStep + 2, Constants.SK_QUALITY_CHECK);
            }

            if (resultContent.contains("\"status\":\"OK\"") || resultContent.contains("\"status\": \"OK\"")) {
                System.out.println("[" + getLocalName() + "] Product is OK.");
            }
        }
        return result.getPerformative() == ACLMessage.INFORM;
    }

    private long getExecutionResultTimeoutMs(String skill) {
        switch (skill) {
            case Constants.SK_GLUE_TYPE_A:
            case Constants.SK_GLUE_TYPE_B:
            case Constants.SK_GLUE_TYPE_C:
                return Constants.SIM_GLUE_TIMEOUT_MS + Constants.PRODUCT_TIMEOUT_MARGIN_MS;
            default:
                return Constants.SIM_RESOURCE_TIMEOUT_MS + Constants.PRODUCT_TIMEOUT_MARGIN_MS;
        }
    }

    private long getTransportResultTimeoutMs() {
        return Constants.SIM_TRANSPORT_TIMEOUT_MS + Constants.PRODUCT_TIMEOUT_MARGIN_MS;
    }

    private double estimateRemainingPlanCost(
            AID chosenResource,
            int stepIndex,
            String fromLocation,
            double currentStepCost
    ) {
        String chosenLocation = getKnownResourceLocation(chosenResource);
        if (chosenLocation == null) {
            return Double.MAX_VALUE;
        }

        double total = currentStepCost;
        String simulatedLocation = chosenLocation;

        for (int nextStep = stepIndex + 1; nextStep < executionPlan.size(); nextStep++) {
            String nextSkill = executionPlan.get(nextStep);
            EstimatedChoice bestNext = estimateBestChoiceForSkill(nextSkill, simulatedLocation);

            if (bestNext == null) {
                return Double.MAX_VALUE;
            }

            total += bestNext.cost;
            simulatedLocation = bestNext.location;
        }

        return total;
    }

    private EstimatedChoice estimateBestChoiceForSkill(String skill, String fromLocation) {
        AID[] candidates = searchDF(Constants.DFSERVICE_RESOURCE, skill);
        if (candidates.length == 0) {
            return null;
        }

        EstimatedChoice best = null;

        for (AID candidate : candidates) {
            String candidateLocation = getKnownResourceLocation(candidate);
            if (candidateLocation == null) {
                continue;
            }

            double travelCost = getEstimatedTravelTimeMs(fromLocation, candidateLocation);
            double processCost = getEstimatedProcessTime(skill);
            double totalCost = travelCost + processCost;

            if (best == null || totalCost < best.cost) {
                best = new EstimatedChoice(candidate, candidateLocation, totalCost);
            }
        }

        return best;
    }

    private String getKnownResourceLocation(AID resource) {
        String name = resource.getLocalName();

        if ("Operator".equals(name)) return "Source";
        if ("GlueStation1".equals(name)) return "GlueStation1";
        if ("GlueStation2".equals(name)) return "GlueStation2";
        if ("QualityControlStation1".equals(name)) return "QualityControlStation1";
        if ("QualityControlStation2".equals(name)) return "QualityControlStation2";

        return null;
    }

    private double getEstimatedTravelTimeMs(String from, String to) {
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

    private double getEstimatedProcessTime(String skill) {
        switch (skill) {
            case Constants.SK_PICK_UP: return 2000;
            case Constants.SK_DROP: return 2000;
            case Constants.SK_SINK: return 1000;
            case Constants.SK_GLUE_TYPE_A: return 5000;
            case Constants.SK_GLUE_TYPE_B: return 5000;
            case Constants.SK_GLUE_TYPE_C: return 5000;
            case Constants.SK_QUALITY_CHECK: return 4000;
            default: return 3000;
        }
    }

    private static class ProposalData {
        AID agent;
        double metric;
        double totalEstimatedCost;
        String knownLocation;

        ProposalData(AID agent, double metric, double totalEstimatedCost, String knownLocation) {
            this.agent = agent;
            this.metric = metric;
            this.totalEstimatedCost = totalEstimatedCost;
            this.knownLocation = knownLocation;
        }
    }

    private static class EstimatedChoice {
        AID agent;
        String location;
        double cost;

        EstimatedChoice(AID agent, String location, double cost) {
            this.agent = agent;
            this.location = location;
            this.cost = cost;
        }
    }
}
