package com.messagerelay.protocol;

import com.messagerelay.config.RelayLimits;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/* "Where does the message start/end?" */

public class FrameCodec {

    public String readFrame(
            DataInputStream input
    ) throws IOException {

        int length = input.readInt();

        if (length <= 0
                || length > RelayLimits.MAX_FRAME_BYTES) {
            throw new IOException(
                    "Invalid frame length: " + length
            );
        }

        byte[] payload =
                input.readNBytes(length);

        if (payload.length != length) {
            throw new IOException(
                    "Incomplete frame"
            );
        }

        return new String(
                payload,
                StandardCharsets.UTF_8
        );
    }

    public void writeFrame(
            DataOutputStream output,
            String message
    ) throws IOException {

        byte[] payload =
                message.getBytes(
                        StandardCharsets.UTF_8
                );

        if (payload.length
                > RelayLimits.MAX_FRAME_BYTES) {
            throw new IOException(
                    "Frame too large"
            );
        }

        output.writeInt(payload.length);
        output.write(payload);
        output.flush();
    }
}
