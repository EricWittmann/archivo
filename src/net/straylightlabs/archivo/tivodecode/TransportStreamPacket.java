/*
 * Copyright 2015 Todd Kulesza <todd@dropline.net>.
 *
 * This file is part of Archivo.
 *
 * Archivo is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Archivo is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Archivo.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.straylightlabs.archivo.tivodecode;

import java.io.IOException;

public class TransportStreamPacket {
    private long packetId;
    private Header header;

    public boolean readFrom(CountingDataInputStream inputStream) throws IOException {
        header = readHeader(inputStream);
        if (!header.isValid()) {
            System.err.format("Invalid TS packet header%n");
            return false;
        }
        // Read the rest of the packet
        int bytesToRead = TransportStream.TS_FRAME_SIZE - header.getLength();
        int bytesRead, totalBytesRead = 0;
        byte[] buffer = new byte[bytesToRead];
        do {
            bytesRead = inputStream.read(buffer, totalBytesRead, bytesToRead - totalBytesRead);
            totalBytesRead += bytesRead;
        } while (bytesRead != -1 && totalBytesRead < bytesToRead);

        return (totalBytesRead == bytesToRead);
    }

    private Header readHeader(CountingDataInputStream inputStream) throws IOException {
        int headerLength = Integer.BYTES;
        int headerBits = inputStream.readInt();
        header = new Header(headerBits);
        if (header.hasAdaptationField) {
            byte adaptationFieldLength = inputStream.readByte();
            byte adaptationFieldBits = inputStream.readByte();
            inputStream.skipBytes(adaptationFieldLength - 1);
            headerLength += (adaptationFieldLength + 1);
        }
        header.setLength(headerLength);

        return header;
    }

    public void setPacketId(long id) {
        packetId = id;
    }

    public PacketType getPacketType() {
        return header.getType();
    }

    public int getPID() {
        return header.getPID();
    }

    @Override
    public String toString() {
        return String.format("====== Packet: %d ======", packetId);
    }

    public enum PacketType {
        PROGRAM_ASSOCIATION_TABLE(0x0000, 0x0000),
        CONDITIONAL_ACCESS_TABLE(0x0001, 0x0001),
        RESERVED(0x0002, 0x000F),
        NETWORK_INFORMATION_TABLE(0x0010, 0x0010),
        SERVICE_DESCRIPTION_TABLE(0x0011, 0x0011),
        EVENT_INFORMATION_TABLE(0x0012, 0x0012),
        RUNNING_STATUS_TABLE(0x0013, 0x0013),
        TIME_DATE_TABLE(0x0014, 0x0014),
        RESERVED2(0x0015, 0x001F),
        AUDIO_VIDEO_PRIVATE_DATA(0x0020, 0x1FFE),
        NONE(0xFFFF, 0xFFFF);

        private final int lowVal;
        private final int highVal;

        PacketType(int lowVal, int highVal) {
            this.lowVal = lowVal;
            this.highVal = highVal;
        }

        public static PacketType valueOf(int pid) {
            for (PacketType type : values()) {
                if (type.lowVal <= pid && pid <= type.highVal) {
                    return type;
                }
            }
            return NONE;
        }
    }

    private static class Header {
        private int length;
        private int sync;
        private boolean hasTransportError;
        private boolean isPayloadUnitStart;
        private boolean isPriority;
        private int pid;
        private int scramblingControl;
        private boolean hasAdaptationField;
        private boolean hasPayloadData;
        private int counter;

        public Header(int bits) {
            initFromBits(bits);
        }

        private void initFromBits(int bits) {
            sync = (bits & 0xff000000) >> 24;
            hasTransportError = ((bits & 0x00800000) >> 23) == 1;
            isPayloadUnitStart = ((bits & 0x00400000) >> 22) == 1;
            isPriority = ((bits & 0x00200000) >> 21) == 1;
            pid = (bits & 0x001FFF00) >> 8;
            scramblingControl = (bits & 0x000000C0) >> 6;
            hasAdaptationField = ((bits & 0x00000020) >> 5) == 1;
            hasPayloadData = ((bits & 0x00000010) >> 4) == 1;
            counter = (bits & 0x0000000F);
        }

        public void setLength(int val) {
            length = val;
        }

        public int getLength() {
            return length;
        }

        public boolean isValid() {
            return sync == 0x47;
        }

        public boolean isHasTransportError() {
            return hasTransportError;
        }

        public boolean isPayloadUnitStart() {
            return isPayloadUnitStart;
        }

        public boolean isPriority() {
            return isPriority;
        }

        public int getPID() {
            return pid;
        }

        public PacketType getType() {
            return PacketType.valueOf(pid);
        }

        public int getScramblingControl() {
            return scramblingControl;
        }

        public boolean isHasAdaptationField() {
            return hasAdaptationField;
        }

        public boolean isHasPayloadData() {
            return hasPayloadData;
        }

        public int getCounter() {
            return counter;
        }
    }

    private static class AdaptationField {

    }
}
