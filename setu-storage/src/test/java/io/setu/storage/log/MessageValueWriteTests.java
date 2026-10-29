package io.setu.storage.log;

import net.openhft.chronicle.bytes.Bytes;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MessageValueWriteTests {
    @Test void chronicleBulkWritesMatchStreamWritesForOwnedValuesAndFrameSlices() throws Exception {
        byte[] source = new byte[2 * 1024 * 1024 + 97];
        new Random(42).nextBytes(source);
        var frame = MessageFrame.readFrom(new DataInputStream(new ByteArrayInputStream(source)), source.length);
        Bytes<?> output = Bytes.allocateElasticDirect(32);
        try {
            for (int length : new int[]{0, 1, 7, 8, 31, 1024, 65539, 1024 * 1024 + 3}) {
                for (var value : new MessageValue[]{
                        MessageValue.copyOf(Arrays.copyOfRange(source, 19, 19 + length)),
                        frame.valueAt(19, length), frame.valueAt(source.length - length, length)}) {
                    var expected = new ByteArrayOutputStream();
                    var stream = new DataOutputStream(expected);
                    stream.writeByte(73);
                    value.writeTo(stream);
                    stream.writeByte(91);

                    output.clear();
                    output.writeByte((byte) 73);
                    value.writeTo(output);
                    output.writeByte((byte) 91);
                    assertEquals(length + 2L, output.writePosition());
                    assertArrayEquals(expected.toByteArray(), output.toByteArray());
                }
            }
            assertArrayEquals(source, frame.copy(), "writing slices must not change the shared frame");
        } finally {
            output.releaseLast();
        }
    }
}
