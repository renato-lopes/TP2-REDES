
package tp2;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.DatagramPacket;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;
/**
 *
 * @author renatojuniortmp
 */
public class RouterRIP extends Thread{

    private DatagramSocket socket;  // Socket UDP
    private String ip;              // IP do roteador
    private int period;             // Período de atualização

    public static final int ROUTER_PORT = 55151;

    private List<RoutingTableEntry> knownRoutes;

    public RouterRIP(String ip, int period) {
        this.ip = ip;
        this.period = period;
        try {
            this.socket = new DatagramSocket(ROUTER_PORT, InetAddress.getByName(ip));
        } catch (UnknownHostException | SocketException ex) {
            System.out.println("Erro ao criar o socket! " + ex.getLocalizedMessage());
            System.exit(0);
        }
        this.knownRoutes = new ArrayList<>();
    }

    public void run(){
        byte[] buf = new byte[1024];
        DatagramPacket p = new DatagramPacket(buf,buf.length);
        while(true){
            try{
                socket.receive(p);
                receiveMessage(p);
            }catch(Exception e0){
                System.out.println("Error: " + e0.getMessage());
            }
        }
    }
    public void receiveMessage(DatagramPacket p){
        try{
            JSONObject messageJson = new JSONObject(new String(p.getData()));
            if(messageJson.getString("type").equals("data")){
                if(messageJson.getString("destination").equals(this.ip)){
//                        System.out.println("Message received by: " + this.ip);
//                        System.out.println("Sent by: " + messageJson.getString("source"));
                    System.out.println(messageJson.getString("payload"));
                }
                else{
                    DataMessage m = new DataMessage(messageJson.getString("source"),messageJson.getString("destination"),messageJson.getString("payload"));
                    for(RoutingTableEntry i:this.knownRoutes){
                        if(i.getIpDestination().equals(messageJson.getString("destination"))){
                            sendMessage(m,i.getNextHop());
                            break;
                        }
                    }
                }
            }
            else if(messageJson.getString("type").equals("update")){
                if(messageJson.getString("destination").equals(this.ip)){
                    Map<String,Object> mMap = messageJson.getJSONObject("distances").toMap();
                    HashMap<String,Integer> dists =  new HashMap<String,Integer>();
                    for(String key:mMap.keySet()){
                        dists.put(key,(Integer) mMap.get(key));
                    }

                    for(String key:dists.keySet()){
                        boolean hasKey = false;
                        for(RoutingTableEntry i:this.knownRoutes){
                            if(i.getIpDestination().equals(key)){
                                if(i.getDistance() > dists.get(key)){
                                    System.out.println("new best route!");
                                    i.setNextHop(messageJson.getString("source"));
                                    i.setDistance(dists.get(key));
                                }
                                hasKey = true;
                            } 
                        }
                        if(!hasKey){
                            System.out.println("New route found!");
                            addNewRoute(key,messageJson.getString("source"),dists.get(key));
                        }
                    }
                }
            }
            else if(messageJson.getString("type").equals("trace")){
                if(messageJson.getString("destination").equals(this.ip)){
                    TraceMessage mTrace = new TraceMessage(messageJson.getString("source"),messageJson.getString("destination"));
                    for(int i = 0; i < messageJson.getJSONArray("hops").length(); i++){
                        mTrace.addHop(messageJson.getJSONArray("hops").getString(i));
                    }
                    mTrace.addHop(this.ip);

                    DataMessage mData = new DataMessage(this.ip,messageJson.getString("source"),mTrace.getMessageJson().toString());
                    
                    for(RoutingTableEntry i:this.knownRoutes){
                        if(i.getIpDestination().equals(messageJson.getString("source"))){
                            sendMessage(mData,i.getNextHop());
                            break;
                        }
                    }                    
                }
                else{
                    TraceMessage mTrace = new TraceMessage(messageJson.getString("source"),messageJson.getString("destination"));
                    for(int i = 0; i < messageJson.getJSONArray("hops").length(); i++){
                        mTrace.addHop(messageJson.getJSONArray("hops").getString(i));
                    }
                    mTrace.addHop(this.ip);
                    sendMessage(mTrace,messageJson.getString("destination"));
                }
            }

        }catch(Exception e0){
            System.out.println("Error: " + e0.getMessage());
        }
}
    
    public void printTable(){
        System.out.println("===============================");
        for(RoutingTableEntry i: this.knownRoutes){
            System.out.println("IpDest:" + i.getIpDestination());
            System.out.println("NextHop: " + i.getNextHop());
            System.out.println("Dist: " + i.getDistance() + "\n");            
        }        
    }
    
    public void sendUpdateMessages(){
        ArrayList<String> neibSent = new ArrayList<String>();
        for(RoutingTableEntry i: this.knownRoutes){
            if(!neibSent.contains(i.getNextHop())){
                UpdateMessage updateMessage = new UpdateMessage(this.ip,i.getNextHop());
                for(RoutingTableEntry j: this.knownRoutes){
                    if(!i.getNextHop().equals(j.getIpDestination())){
                        updateMessage.addDistance(j.getIpDestination(),j.getDistance());
                    }
                }
                sendMessage(updateMessage,i.getNextHop());
                neibSent.add(i.getNextHop());
            }
        }
    }
    
    public void sendTraceMessage(String ipDest){
        TraceMessage tMessage = new TraceMessage(this.ip,ipDest);
        tMessage.addHop(this.ip);
        for(RoutingTableEntry i:this.knownRoutes){
            if(i.getIpDestination().equals(ipDest)){
                sendMessage(tMessage,i.getNextHop());
                break;
            }
        }
    }
    
    public void sendDataMessage(String ipDest,String payload){
        DataMessage dMessage = new DataMessage(this.ip,ipDest,payload);
        for(RoutingTableEntry i:this.knownRoutes){
            if(i.getIpDestination().equals(ipDest)){
                sendMessage(dMessage,i.getNextHop());
            }
        }
    }
    
    public void sendMessage(Message m, String ipToSend) {
        try {
            DatagramPacket p = new DatagramPacket(m.getMessageJson().getBytes(), m.getMessageJson().getBytes().length);
            p.setAddress(InetAddress.getByName(ipToSend));
            p.setPort(ROUTER_PORT);
            socket.send(p);

        } catch (IOException ex ) {
            System.out.println("Erro ao enviar o pacote! " + ex.getLocalizedMessage());
            System.out.println("É so isso.. não tem mais jeito... acabou!");
            System.exit(0);
        }
    }

    /**
     * Adiciona uma nova entrada na tabela de roteamento.
     * @param ipDest o ip do destino.
     * @param ipToSend o ip do next hop.
     * @param dist o peso do enlace;
     */
    void addNewRoute(String ipDest, String ipToSend, int dist) {
        RoutingTableEntry newRoute = new RoutingTableEntry();
        newRoute.setDistance(dist);
        newRoute.setIpDestination(ipDest);
        newRoute.setNextHop(ipToSend);
        newRoute.setAddTime(System.currentTimeMillis());
        this.knownRoutes.add(newRoute);
        System.out.println("New route discovered!");
    }
    
    void deleteRoute(String ipDest){
        for(RoutingTableEntry i:this.knownRoutes){
            if(i.getNextHop().equals(ipDest)){
                this.knownRoutes.remove(i);
            }
        }
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
