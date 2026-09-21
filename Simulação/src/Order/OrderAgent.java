package Order;

import Product.ProductAgent;
import jade.core.Agent;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.AID;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.*;

public class OrderAgent extends Agent {

    private int productA;
    private int productB;
    private int productC;

    private int productCounter = 0;

    private static final int MAX_ACTIVE = 2;

    // fila com os tipos ainda por lançar, já balanceados
    private Queue<String> launchQueue = new LinkedList<>();

    // produtos ativos no sistema
    private Set<String> activeProducts = new HashSet<>();

    // produtos já lançados mas ainda à espera da libertação inicial
    private Set<String> waitingAtSource = new HashSet<>();

    // produtos já libertados para continuar após o pick
    private Set<String> releasedProducts = new HashSet<>();

    @Override
    protected void setup() {
        Object[] args = this.getArguments();
        this.productA = Integer.parseInt((String) args[0]);
        this.productB = Integer.parseInt((String) args[1]);
        this.productC = Integer.parseInt((String) args[2]);

        System.out.println("Order Received ProductsA " + productA +
                " ProductsB " + productB + " ProductsC " + productC);

        // construir sequência balanceada
        buildBalancedLaunchQueue();

        addBehaviour(new OneShotBehaviour() {
            @Override
            public void action() {
                try {
                    launchUpToLimit();
                } catch (StaleProxyException e) {
                    e.printStackTrace();
                }
            }
        });

        addBehaviour(new CyclicBehaviour() {
            @Override
            public void action() {

                MessageTemplate mtReady = MessageTemplate.and(
                        MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                        MessageTemplate.MatchOntology("ont_ready_at_source")
                );

                MessageTemplate mtDone = MessageTemplate.and(
                        MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                        MessageTemplate.MatchOntology(Utilities.Constants.ONTOLOGY_PRODUCT_DONE)
                );

                ACLMessage msg = receive(mtReady);

                if (msg != null) {
                    handleReadyAtSource(msg);
                    return;
                }

                msg = receive(mtDone);

                if (msg != null) {
                    handleProductDone(msg);
                    return;
                }

                block();
            }
        });
    }

    private void buildBalancedLaunchQueue() {
        int a = productA;
        int b = productB;
        int c = productC;

        String last1 = null;
        String last2 = null;

        while (a > 0 || b > 0 || c > 0) {
            List<Candidate> candidates = new ArrayList<>();

            if (a > 0) candidates.add(new Candidate("A", a));
            if (b > 0) candidates.add(new Candidate("B", b));
            if (c > 0) candidates.add(new Candidate("C", c));

            // ordenar por quantidade restante (descendente)
            candidates.sort((x, y) -> Integer.compare(y.remaining, x.remaining));

            String chosen = null;

            for (Candidate cand : candidates) {
                // evitar 3 iguais seguidos quando possível
                if (last1 != null && last2 != null &&
                        last1.equals(cand.type) && last2.equals(cand.type)) {
                    continue;
                }
                chosen = cand.type;
                break;
            }

            // se não foi possível evitar repetição, escolhe o mais abundante
            if (chosen == null && !candidates.isEmpty()) {
                chosen = candidates.get(0).type;
            }

            if (chosen == null) break;

            launchQueue.add(chosen);

            if (chosen.equals("A")) a--;
            else if (chosen.equals("B")) b--;
            else if (chosen.equals("C")) c--;

            last2 = last1;
            last1 = chosen;
        }

        System.out.println("Balanced launch sequence: " + launchQueue);
    }

    private void launchUpToLimit() throws StaleProxyException {
        while (activeProducts.size() < MAX_ACTIVE && !launchQueue.isEmpty()) {
            String type = launchQueue.poll();
            String productId = launchProduct(type);
            activeProducts.add(productId);
        }

        if (launchQueue.isEmpty() && activeProducts.isEmpty()) {
            System.out.println("All products completed for this order.");
        }
    }

    private String launchProduct(String productType) throws StaleProxyException {
        String id = getLocalName() + "_Product" + this.productCounter;

        Object[] productArgs = new Object[]{id, productType, getLocalName()};

        AgentController agent = this.getContainerController().createNewAgent(
                id,
                "Product.ProductAgent",
                productArgs
        );
        agent.start();

        this.productCounter++;

        System.out.println("Product launched: " + id + " Type: " + productType);
        return id;
    }

    private void handleReadyAtSource(ACLMessage msg) {
        String productId = msg.getContent();
        waitingAtSource.add(productId);

        System.out.println("[OA] Product ready at source: " + productId);

        // liberta quando todos os produtos ativos atuais já fizeram o pick
        // e ainda não foram libertados
        if (allActiveProductsReadyAndNotReleased()) {
            releaseWaitingProducts();
        }
    }

    private boolean allActiveProductsReadyAndNotReleased() {
        for (String pid : activeProducts) {
            if (!releasedProducts.contains(pid) && !waitingAtSource.contains(pid)) {
                return false;
            }
        }
        return !activeProducts.isEmpty();
    }

    private void releaseWaitingProducts() {
        System.out.println("[OA] Releasing products for transport: " + waitingAtSource);

        for (String productId : new HashSet<>(waitingAtSource)) {
            ACLMessage release = new ACLMessage(ACLMessage.INFORM);
            release.addReceiver(new AID(productId, AID.ISLOCALNAME));
            release.setOntology("ont_release_transport");
            release.setContent("go");
            send(release);

            releasedProducts.add(productId);
        }

        waitingAtSource.clear();
    }

    private void handleProductDone(ACLMessage msg) {
        String productId = msg.getContent();

        System.out.println("[OA] Product completed: " + productId);

        activeProducts.remove(productId);
        waitingAtSource.remove(productId);
        releasedProducts.remove(productId);

        try {
            launchUpToLimit();
        } catch (StaleProxyException e) {
            e.printStackTrace();
        }
    }

    private static class Candidate {
        String type;
        int remaining;

        Candidate(String type, int remaining) {
            this.type = type;
            this.remaining = remaining;
        }
    }
}
