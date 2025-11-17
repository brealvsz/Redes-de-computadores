package protocol;

import java.io.Serializable;

public class Packet implements Serializable {
    private PacketType type;
    private int sequenceNumber;
    private long checksum;
    private byte[] data;

    //contrutor para pacotes que carregam dados de fato
    public Packet(int sequenceNumber, byte[] data){
        this.type = PacketType.DATA;
        this.sequenceNumber = sequenceNumber;
        this.data = data;
        this.checksum = 0;
    }

    //construtor para pacotes de controle
    public Packet(PacketType type, int sequenceNumber){
        this.type = type;
        this.sequenceNumber = sequenceNumber;
        this.data = new byte[0];
        this.checksum = 0;
    }

    public PacketType getType(){
        return type;
    }

    public int getSequenceNumber(){
        return sequenceNumber;
    }

    public long getChecksum(){
        return checksum;
    }

    public byte[] getData(){
        return data;
    }

    public void setChecksum(long checksum){
        this.checksum = checksum;
    }

}
