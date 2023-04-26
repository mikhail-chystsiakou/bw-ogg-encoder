package org.example;

import com.google.common.io.LittleEndianDataInputStream;
import com.google.common.primitives.Bytes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class OggEncoder {
    private OutputStream outputStream;
    private LittleEndianDataInputStream in;
    private final Queue<byte[]> lastPageLeftAudioDataPackets = new LinkedList<>();
    private boolean isLastReadPageCompleted = true;
    private long streamId;
    private boolean isEnd = false;

    public OggEncoder(InputStream inputStream, OutputStream outputStream) {
        this.outputStream = outputStream;
        this.in = new LittleEndianDataInputStream(inputStream);;
    }

    public void encode() throws IOException {
        OggPage headerPage = readIdHeader();
        byte[] outputBinary = headerPage.dump();
        outputStream.write(outputBinary);
        outputStream.write(readCommentHeader());

        while (true) {
            byte[] audioDataPacket = readAudioPacket();
            if (audioDataPacket == null) break;
            outputStream.write(audioDataPacket);
        }
        outputStream.close();
    }

    public byte[] readAudioPacket() throws IOException {
        if (lastPageLeftAudioDataPackets.isEmpty()) {
            if (isEnd) {
                return null;
            }

            byte[] data = new byte[0];
            while (true) {
                OggPage oggPage = readPage(streamId);
                if (oggPage == null) {
                    throw new RuntimeException("Corrupted opus binary data");
                }
                encodePage(oggPage);
                oggPage.setCheckSum(0);

                isEnd = oggPage.isEOS();
                isLastReadPageCompleted = oggPage.isCompleted();
                lastPageLeftAudioDataPackets.add(oggPage.dump());
                data = Bytes.concat(data, lastPageLeftAudioDataPackets.poll());
                if (isEnd || oggPage.getDataPackets().size() != 1 || oggPage.isCompleted()) {
                    break;
                }
            }
//            return AudioDataPacket.from(data, idHeader.getStreamCount());
            return data;
        }

        byte[] data = lastPageLeftAudioDataPackets.poll();

        if (isLastReadPageCompleted || !lastPageLeftAudioDataPackets.isEmpty()) {
//            return AudioDataPacket.from(data, idHeader.getStreamCount());
            return data;
        }

        while (true) {
            OggPage oggPage = readPage(streamId);
            if (oggPage == null) {
                throw new RuntimeException("Corrupted opus binary data");
            }
            encodePage(oggPage);
            oggPage.setCheckSum(0);

            isEnd = oggPage.isEOS();
            isLastReadPageCompleted = oggPage.isCompleted();
            lastPageLeftAudioDataPackets.add(oggPage.dump());
            data = Bytes.concat(data, lastPageLeftAudioDataPackets.poll());
            if (isEnd || oggPage.getDataPackets().size() != 1 || oggPage.isCompleted()) {
                break;
            }
        }
//        return AudioDataPacket.from(data, idHeader.getStreamCount());
        return data;
    }



    private OggPage encodePage(OggPage oggPage) {
        List<byte[]> bytes = oggPage.getDataPackets();
        List<byte[]> outputBytes = new ArrayList<>(bytes.size());
        for (byte[] packet : bytes) {
            byte[] xoredPacket = new byte[packet.length];
            for (int i = 0; i < packet.length; i++) {
                xoredPacket[i] = (byte) (255 ^ packet[i]);
            }
            outputBytes.add(xoredPacket);
        }
        oggPage.setDataPackets(outputBytes);
//        byte[] page = oggPage.dump();
//        int checkSum = CRCUtil.getCRC(page);
//        oggPage.setCheckSum(checkSum);
        return oggPage;
    }


    private OggPage readIdHeader() throws IOException {
        OggPage oggPage = readOpusBosPage();
        streamId = oggPage.getSerialNum();
        return oggPage;
    }

    private OggPage readOpusBosPage() throws IOException {
        while (true) {
            OggPage oggPage = readPage();
            if (oggPage == null) {
                throw new RuntimeException("No ID Header data in this opus file");
            }

            if (oggPage.isBOS()) {
                if (oggPage.getDataPackets().size() > 1) {
                    throw new RuntimeException("The ID Header Ogg page must NOT contain other data");
                }
                return oggPage;
            }
        }
    }


    private byte[] readCommentHeader() throws IOException {
        byte[] commentHeaderData = new byte[0];
        while (true) {
            OggPage currentPage = readPage(streamId);
            byte[] currentPagePackets = currentPage.dump();
            commentHeaderData = Bytes.concat(commentHeaderData, currentPagePackets);
            if (currentPage.getGranulePosition() == 0) break;
        }
        return commentHeaderData;
    }


    private OggPage nextPage() throws IOException {
        OggPage oggPage = OggPage.empty();
        int version = in.readUnsignedByte();
        if (version != 0) {
            throw new RuntimeException("Unsupported Ogg page version: " + version);
        }
        oggPage.setFlag(in.readUnsignedByte());
        oggPage.setGranulePosition(in.readLong());
        oggPage.setSerialNum(Integer.toUnsignedLong(in.readInt()));
        oggPage.setSeqNum(Integer.toUnsignedLong(in.readInt()));
        oggPage.setCheckSum(in.readInt());
        int segCount = in.readUnsignedByte();
        byte[] laceValues = in.readNBytes(segCount);

        int packetLen = 0;
        for (byte laceValue : laceValues) {
            int segLen = Byte.toUnsignedInt(laceValue);
            packetLen += segLen;
            if (segLen < OggPage.MAX_LACE_VALUE) {
                byte[] data = in.readNBytes(packetLen);
                oggPage.addDataPacket(data);
                packetLen = 0;
            }
        }
        if (packetLen != 0) {
            byte[] data = in.readNBytes(packetLen);
            oggPage.addPartialDataPacket(data);
        }
        return oggPage;
    }

    private boolean hasNextPage() throws IOException {
        int posOfPattern = 0;
        while (posOfPattern < OggPage.CAPTURE_PATTERN.length) {
            int b = in.read();
            if (b == -1) {
                return false;
            }
            if (b == OggPage.CAPTURE_PATTERN[posOfPattern]) {
                posOfPattern++;
            } else {
                posOfPattern = (b == OggPage.CAPTURE_PATTERN[0] ? 1 : 0);
            }
        }
        return true;
    }

    public OggPage readPage() throws IOException {
        if (hasNextPage()) {
            return nextPage();
        }
        return null;
    }

    private OggPage readPage(long serialNum) throws IOException {
        while (hasNextPage()) {
            OggPage oggPage = nextPage();
            if (oggPage.getSerialNum() == serialNum) {
                return oggPage;
            }
        }
        return null;
    }
}
