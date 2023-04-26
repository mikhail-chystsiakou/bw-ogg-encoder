package org.example;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class Main {

    public static void main(String[] args) throws Exception {
        String prefix = "D:\\workspace\\bewired\\encoder\\voices\\";


        FileInputStream origInStream = new FileInputStream(prefix + "full.ogg");
        OutputStream encodedOutStream = new FileOutputStream(prefix + "decoded1.ogg");
        OggEncoder oggEncoder = new OggEncoder(origInStream, encodedOutStream);
        oggEncoder.encode();

        origInStream.close();
        encodedOutStream.close();
    }
}
