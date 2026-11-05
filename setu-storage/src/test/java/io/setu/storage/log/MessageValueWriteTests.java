package io.setu.storage.log;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MessageValueWriteTests {
    @Test void payloadBufferCopiesMatchStreamWritesForOwnedValuesAndFrameSlices() throws Exception {
        byte[] source = new byte[2 * 1024 * 1024 + 97];
        new Random(42).nextBytes(source);
        var frame = MessageFrame.readFrom(new DataInputStream(new ByteArrayInputStream(source)), source.length);
        for (boolean direct : new boolean[]{false, true}) {
            for (int length : new int[]{0, 1, 7, 8, 31, 1024, 65539, 1024 * 1024 + 3}) {
                for (var value : new MessageValue[]{
                        MessageValue.copyOf(Arrays.copyOfRange(source, 19, 19 + length)),
                        frame.valueAt(19, length), frame.valueAt(source.length - length, length)}) {
                    var expected = new ByteArrayOutputStream();
                    var stream = new DataOutputStream(expected);
                    stream.writeByte(73);
                    value.writeTo(stream);
                    stream.writeByte(91);

                    ByteBuffer output = direct ? ByteBuffer.allocateDirect(length + 2) : ByteBuffer.allocate(length + 2);
                    output.put((byte) 73);
                    value.copyTo(output);
                    output.put((byte) 91);
                    assertEquals(length + 2, output.position());
                    byte[] actual = new byte[output.position()];
                    output.flip().get(actual);
                    assertArrayEquals(expected.toByteArray(), actual);
                }
            }
            assertArrayEquals(source, frame.copy(), "writing slices must not change the shared frame");
        }
    }
}
