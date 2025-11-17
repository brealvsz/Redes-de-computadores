package protocol;

import java.util.zip.CRC32;

public class Checksum {

    public static long calculate (byte[] data){
        if(data == null){
            return 0;
        }
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }
}
