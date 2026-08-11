package com.henry.standbyclock;

import static org.junit.Assert.assertEquals;

import java.io.IOException;

import org.junit.Test;

public final class YeelightClientTest {
    @Test
    public void requestUsesCompactJsonAndRequiredCrLfTerminator() {
        assertEquals(
                "{\"id\":7,\"method\":\"get_prop\",\"params\":[\"power\"]}\r\n",
                YeelightClient.buildRequest(7, "get_prop", "[\"power\"]"));
    }

    @Test
    public void parsesPowerStatesWithOrWithoutWhitespace() throws IOException {
        assertEquals(
                YeelightClient.PowerState.ON,
                YeelightClient.parsePowerResponse("{\"id\":1,\"result\":[\"on\"]}"));
        assertEquals(
                YeelightClient.PowerState.OFF,
                YeelightClient.parsePowerResponse("{ \"result\" : [ \"off\" ] }"));
    }

    @Test(expected = IOException.class)
    public void malformedPowerResponseIsRejected() throws IOException {
        YeelightClient.parsePowerResponse("{\"id\":1,\"result\":[\"ok\"]}");
    }
}
