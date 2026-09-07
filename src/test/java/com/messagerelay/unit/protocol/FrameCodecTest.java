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

    // Protocol framing: a length-prefixed payload round-trips unchanged.
    @Test
    void given_message_when_writing_and_reading_frame_then_original_message_is_returned() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);

        frameCodec.writeFrame(output, "hello");

        DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(bytes.toByteArray())
        );

        String result = frameCodec.readFrame(input);

        assertEquals("hello", result);
    }

    // Wire-format requirement: frame lengths count UTF-8 bytes rather than characters.
    @Test
    void given_utf8_message_when_writing_and_reading_frame_then_original_message_is_returned() throws Exception {
        String message = "Hello 👋 café";

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);

        frameCodec.writeFrame(output, message);

        DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(bytes.toByteArray())
        );

        assertEquals(message, frameCodec.readFrame(input));
    }

    // Invalid-input behavior: empty frames are rejected.
    @Test
    void given_zero_length_frame_when_reading_frame_then_io_exception_is_thrown() {
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

    // Invalid-input behavior: negative frame lengths are rejected before allocation.
    @Test
    void given_negative_frame_length_when_reading_frame_then_io_exception_is_thrown() {
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

    // Buffer safety: truncated payloads are rejected instead of partially decoded.
    @Test
    void given_incomplete_frame_when_reading_frame_then_io_exception_is_thrown() throws Exception {
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

    // Message-size bound: outbound frames larger than 64 KiB are rejected.
    @Test
    void given_message_larger_than_maximum_when_writing_frame_then_io_exception_is_thrown() {
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
