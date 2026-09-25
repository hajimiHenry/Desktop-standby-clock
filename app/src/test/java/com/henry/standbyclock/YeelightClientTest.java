package com.henry.standbyclock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

/** Yeelight 协议的报文拼装与响应解析测试，不涉及真实网络连接。 */
public final class YeelightClientTest {
    /**
     * 请求必须是紧凑 JSON（不能有多余空格）且以 \r\n 结尾——
     * 这是协议的硬性要求，少了灯就不理你。
     */
    @Test
    public void requestUsesCompactJsonAndRequiredCrLfTerminator() {
        assertEquals(
                "{\"id\":7,\"method\":\"get_prop\",\"params\":[\"power\"]}\r\n",
                YeelightClient.buildRequest(7, "get_prop", "[\"power\"]"));
    }

    /**
     * 解析开关状态时要容忍空格。不同固件版本的灯返回的 JSON 排版不一样，
     * 正则里的 \s* 就是为此准备的。
     */
    @Test
    public void parsesPowerStatesWithOrWithoutWhitespace() throws IOException {
        assertEquals(
                YeelightClient.PowerState.ON,
                YeelightClient.parsePowerResponse("{\"id\":1,\"result\":[\"on\"]}"));
        assertEquals(
                YeelightClient.PowerState.OFF,
                YeelightClient.parsePowerResponse("{ \"result\" : [ \"off\" ] }"));
    }

    /**
     * 收到 "ok" 而不是 "on"/"off" 时要报错。
     * 这种响应是 toggle 命令的回执，拿它当开关状态解析就错了。
     */
    @Test(expected = IOException.class)
    public void malformedPowerResponseIsRejected() throws IOException {
        YeelightClient.parsePowerResponse("{\"id\":1,\"result\":[\"ok\"]}");
    }

    /**
     * 状态变化时灯会先推一行 props 通知，再回真正的回执（2026-09-25 实测原文）。
     * 通知行必须被判为"不是回执"，否则 toggle 成功了也会被当成失败。
     */
    @Test
    public void propsNotificationIsNotAReply() {
        assertFalse(YeelightClient.isReplyTo(
                "{\"method\":\"props\",\"params\":{\"power\":\"on\"}}", 1));
        assertTrue(YeelightClient.isReplyTo("{\"id\":1,\"result\":[\"ok\"]}", 1));
        assertTrue(YeelightClient.isReplyTo(
                "{\"id\":3, \"error\":{\"code\":-5001,\"message\":\"invalid params\"}}", 3));
    }

    /** id 要整段匹配：等 1 号回执时，12 号的回执不能冒充。 */
    @Test
    public void replyIdMustMatchExactly() {
        assertFalse(YeelightClient.isReplyTo("{\"id\":12,\"result\":[\"ok\"]}", 1));
        assertFalse(YeelightClient.isReplyTo("{\"id\":1,\"result\":[\"ok\"]}", 12));
    }
}
