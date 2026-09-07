package com.messagerelay.unit.protocol;

import com.messagerelay.protocol.FrameCodec;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrameCodecTest {

    private final FrameCodec frameCodec = new FrameCodec();

    @Test
    void writesAndReadsFrame() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);

        frameCodec.writeFrame(output, "hello");

        DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(bytes.toByteArray())
        );

        String result = frameCodec.readFrame(input);

        assertEquals("hello", result);
    }

    @Test
    void writesAndReadsUtf8Frame() throws Exception {
        String message = "Hello 👋 café";

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);

        frameCodec.writeFrame(output, message);

        DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(bytes.toByteArray())
        );

        assertEquals(message, frameCodec.readFrame(input));
    }

    @Test
    void rejectsZeroLengthFrame() {
        byte[] invalidFrame = {
                0, 0, 0, 0
        };

        DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(invalidFrame)
        );

        assertThrows(
                IOException.class,
                () -> frameCodec.readFrame(input)
        );
    }

    @Test
    void rejectsNegativeFrameLength() {
        byte[] invalidFrame = {
                -1, -1, -1, -1
        };

        DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(invalidFrame)
        );

        assertThrows(
                IOException.class,
                () -> frameCodec.readFrame(input)
        );
    }

    @Test
    void rejectsIncompleteFrame() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);

        output.writeInt(10);
        output.write(
                "hello".getBytes(StandardCharsets.UTF_8)
        );

        DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(bytes.toByteArray())
        );

        assertThrows(
                IOException.class,
                () -> frameCodec.readFrame(input)
        );
    }

    @Test
    void rejectsFrameLargerThanMaximum() {
        String message = "a".repeat(
                (64 * 1024) + 1
        );

        DataOutputStream output = new DataOutputStream(
                new ByteArrayOutputStream()
        );

        assertThrows(
                IOException.class,
                () -> frameCodec.writeFrame(output, message)
        );
    }
}