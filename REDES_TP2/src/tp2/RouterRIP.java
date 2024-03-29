package tp2;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.DatagramPacket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import json.JSONObject;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 *
 * @author renato
 */
public class RouterRIP extends Thread {

    private DatagramSocket socket;  // Socket UDP
    private String ip;              // IP do roteador
    private int period;             // Período de atualização

    public static final int ROUTER_PORT = 55151;

    private final List<RoutingTableEntry> knownRoutes;
    private UpdateRoutesThread updateRoutesThread;

    public RouterRIP(String ip, int period) {
        this.ip = ip;
        this.period = period;
        try {
            this.socket = new DatagramSocket(ROUTER_PORT, InetAddress.getByName(ip));
        } catch (UnknownHostException | SocketException ex) {
            System.out.println("Erro ao criar o socket! " + ex.getLocalizedMessage());
            System.exit(0);
        }
        this.knownRoutes = Collections.synchronizedList(new ArrayList<>());

        this.updateRoutesThread = new UpdateRoutesThread(this);
        this.updateRoutesThread.start();
    }

    @Override
    public void run() {
        byte[] buf = new byte[1024];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (true) {
            try {
                socket.receive(p);
                receiveMessage(p);
            } catch (IOException e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
    }

    public synchronized void receiveMessage(DatagramPacket p) {

        JSONObject messageJson = new JSONObject(new String(p.getData()));
        switch (messageJson.getString("type")) {
            case "data":
                if (messageJson.getString("destination").equals(this.ip)) { // Mensagem de dados tem como destino este roteador
                    System.out.println(messageJson.getString("payload"));
                } else { // Mensagem de dados deve ser roteada
                    DataMessage m = new DataMessage(messageJson.getString("source"), messageJson.getString("destination"), messageJson.getString("payload"));
                    RoutingTableEntry routeToDestination = this.getBestRouteToDestination(messageJson.getString("destination"));
                    if (routeToDestination != null) {
                        sendMessage(m, routeToDestination.getNextHop());
                    } else {
                        System.out.println("Impossível rotear mensagem de dados para " + messageJson.getString("destination") + ".");
                    }
                }
                break;
            case "update":
                if (messageJson.getString("destination").equals(this.ip)) {
                    Map<String, Object> routesReceived = messageJson.getJSONObject("distances").toMap();
                    for (String key : routesReceived.keySet()) { // Adiciona todas as rotas que receber do update (Necessário para implementar Reroteamento imediato)
                        addNewRoute(key, messageJson.getString("source"), (Integer) routesReceived.get(key));
                    }
                }
                break;
            case "trace":
                if (messageJson.getString("destination").equals(this.ip)) {
                    TraceMessage mTrace = new TraceMessage(messageJson.getString("source"), messageJson.getString("destination"));
                    for (int i = 0; i < messageJson.getJSONArray("hops").length(); i++) {
                        mTrace.addHop(messageJson.getJSONArray("hops").getString(i));
                    }
                    mTrace.addHop(this.ip);

                    DataMessage mData = new DataMessage(this.ip, messageJson.getString("source"), mTrace.getMessageJson());

                    RoutingTableEntry routeToDestination = this.getBestRouteToDestination(messageJson.getString("source"));
                    if (routeToDestination != null) {
                        sendMessage(mData, routeToDestination.getNextHop());
                    } else {
                        System.out.println("Impossível enviar resposta de trace para " + messageJson.getString("source") + ".");
                    }

                } else {
                    TraceMessage mTrace = new TraceMessage(messageJson.getString("source"), messageJson.getString("destination"));
                    for (int i = 0; i < messageJson.getJSONArray("hops").length(); i++) {
                        mTrace.addHop(messageJson.getJSONArray("hops").getString(i));
                    }
                    mTrace.addHop(this.ip);

                    RoutingTableEntry routeToDestination = this.getBestRouteToDestination(messageJson.getString("destination"));
                    if (routeToDestination != null) {
                        sendMessage(mTrace, routeToDestination.getNextHop());
                    } else {
                        System.out.println("Impossível enviar resposta de trace para " + messageJson.getString("destination") + ".");
                    }

                }
                break;
            default:
                break;
        }

    }

    public void sendDataMessage(String ipDest, String payload) {
        DataMessage dMessage = new DataMessage(this.ip, ipDest, payload);
        RoutingTableEntry routeToDestination = this.getBestRouteToDestination(ipDest);
        if (routeToDestination != null) {
            sendMessage(dMessage, routeToDestination.getNextHop());
        } else {
            System.out.println("Impossível enviar mensagem de dados para " + ipDest + ".");
        }
    }

    public void sendMessage(Message m, String ipToSend) {
        try {
            DatagramPacket p = new DatagramPacket(m.getMessageJson().getBytes(), m.getMessageJson().getBytes().length);
            p.setAddress(InetAddress.getByName(ipToSend));
            p.setPort(ROUTER_PORT);
            socket.send(p);

        } catch (IOException ex) {
            System.out.println("Erro ao enviar o pacote! " + ex.getLocalizedMessage());
            System.exit(0);
        }
    }

    /**
     * Adiciona uma nova entrada na tabela de roteamento.
     *
     * @param ipDest o ip do destino.
     * @param ipToSend o ip do next hop.
     * @param dist o peso do enlace;
     */
    public synchronized void addNewRoute(String ipDest, String ipToSend, int dist) {
        RoutingTableEntry newRoute = new RoutingTableEntry();
        newRoute.setDistance(dist);
        newRoute.setIpDestination(ipDest);
        newRoute.setNextHop(ipToSend);
        newRoute.setAddTime(System.currentTimeMillis());
        if (!this.knownRoutes.contains(newRoute)) {
            this.knownRoutes.add(newRoute);
        }
    }

    public void deleteRoute(String ipDest) {
        synchronized(this.knownRoutes) {
            List<RoutingTableEntry> aux = new ArrayList<>();
            for (RoutingTableEntry i : this.knownRoutes) {
                if (i.getNextHop().equals(ipDest)) {
                    aux.add(i);
                }
            }
            this.knownRoutes.removeAll(aux);
        }
    }

    public void sendTraceMessage(String ipDest) {
        TraceMessage tMessage = new TraceMessage(this.ip, ipDest);
        tMessage.addHop(this.ip);
        RoutingTableEntry routeToDestination = this.getBestRouteToDestination(ipDest);
        if (routeToDestination != null) {
            sendMessage(tMessage, routeToDestination.getNextHop());
        } else {
            System.out.println("Impossível enviar mensagem de dados para " + ipDest + ".");
        }
    }

    public synchronized void printTable() {
        System.out.println("===============================");
        for (RoutingTableEntry i : this.knownRoutes) {
            System.out.println("IpDest:" + i.getIpDestination());
            System.out.println("NextHop: " + i.getNextHop());
            System.out.println("Dist: " + i.getDistance() + "\n");
        }
    }

    /**
     * Encontra a rota conhecida para o destino com a menor distância. Retorna
     * null caso não encontre nenhuma.
     *
     * @param ipDest o IP destino.
     * @return a rota mais curta.
     */
    private synchronized RoutingTableEntry getBestRouteToDestination(String ipDest) {
        ArrayList<RoutingTableEntry> bestRoutes = new ArrayList<>();
        int bestDistance = -1;
        // Pega a menor distância
        for (RoutingTableEntry r : this.knownRoutes) {
            if (r.getIpDestination().equals(ipDest) && checkIfNeighbour(r.getNextHop())) {
                if (bestDistance == -1) {
                    bestDistance = r.getDistance();
                } else if (r.getDistance() < bestDistance) {
                    bestDistance = r.getDistance();
                }
            }
        }
        for (RoutingTableEntry r : this.knownRoutes) {
            if (r.getIpDestination().equals(ipDest) && r.getDistance() == bestDistance) {
                bestRoutes.add(r);
            }
        }
        if (bestRoutes.isEmpty()) {
            return null;
        }
        return bestRoutes.get(new Random().nextInt(bestRoutes.size()));
    }
    
    private boolean checkIfNeighbour(String ip) {
        for(RoutingTableEntry r : this.knownRoutes) {
            if(r.getNextHop().equals(ip) && r.getIpDestination().equals(ip)) {
                return true;
            }
        }
        return false;
    }

    public DatagramSocket getSocket() {
        return socket;
    }

    public String getIp() {
        return ip;
    }

    public int getPeriod() {
        return period;
    }

    public List<RoutingTableEntry> getKnownRoutes() {
        return knownRoutes;
    }
}
