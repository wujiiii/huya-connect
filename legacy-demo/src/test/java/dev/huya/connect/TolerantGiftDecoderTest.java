package dev.huya.connect;

import com.qq.tars.protocol.tars.TarsOutputStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TolerantGiftDecoderTest {
    @Test
    void recoversGiftFieldsWhenNewEffectDataHasAnUnknownType() {
        TarsOutputStream output = new TarsOutputStream();
        output.write(23615, 0);
        output.write("payment-1", 1);
        output.write(3, 2);
        output.write(123L, 4);
        output.write("sender", 6);
        output.write("礼遇鸿运一号", 20);
        output.write(7, 27);
        output.write(99L, 39);
        output.write(500L, 41);
        output.write("new effect format", 42);

        TolerantGiftDecoder.Result result = TolerantGiftDecoder.decode(output.toByteArray());

        assertTrue(result.recovered());
        assertEquals(23615, result.message().getIItemType());
        assertEquals("礼遇鸿运一号", result.message().getGiftName());
        assertEquals(3, result.message().getGiftCount());
        assertEquals(500L, result.message().getLPayTotal());
        assertEquals("payment-1", result.message().getStrPayId());
    }
}
