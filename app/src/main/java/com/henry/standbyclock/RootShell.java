package com.henry.standbyclock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 以 root 身份执行 shell 命令的极简封装。
 *
 * <p>主要用途有两个：恢复被划掉的专职时钟界面，以及读取局域网邻居表来按 MAC
 * 找回 DHCP 已改地址的吸顶灯。设备没 root 时 `su` 直接失败，各调用方都有普通路径兜底。
 */
final class RootShell {
    /** root 命令的退出码和标准输出，供需要读取系统状态的调用方使用。 */
    static final class Result {
        final int exitCode;
        final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private RootShell() {}

    /**
     * 执行一条 root 命令并等它跑完。
     *
     * @return 进程退出码，0 表示成功
     */
    static int run(String command) throws IOException, InterruptedException {
        return execute(command, false).exitCode;
    }

    /** 执行 root 命令并返回输出；只用于输出量很小的系统查询命令。 */
    static Result capture(String command) throws IOException, InterruptedException {
        return execute(command, true);
    }

    private static Result execute(String command, boolean captureOutput)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start();
        // 必须把输出读干净再 waitFor：管道缓冲区写满后子进程会阻塞，
        // 那样 waitFor 就会永远等下去（经典的死锁坑）。
        String output = read(process.getInputStream(), captureOutput);
        return new Result(process.waitFor(), output);
    }

    /**
     * 把时钟界面拉回前台。
     *
     * <p>命令里的标志位 0x34000000 是三个 Intent flag 的组合：
     * FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_RESET_TASK_IF_NEEDED，
     * 效果是复用已有的时钟任务栈并清掉其上的其它界面，而不是新开一个实例。
     *
     * @return 0 成功；-1 表示没 root 或命令执行失败；-2 表示等待时线程被中断
     */
    static int bringClockToFront(String packageName) {
        String component = packageName + "/.MainActivity";
        String command = "am start --user 0"
                + " -a android.intent.action.MAIN"
                + " -c android.intent.category.LAUNCHER"
                + " -f 0x34000000"
                + " -n " + component;
        try {
            return run(command);
        } catch (IOException exception) {
            return -1;
        } catch (InterruptedException exception) {
            // 捕获 InterruptedException 会清掉线程的中断标记，这里补回去，
            // 否则外层的线程池就没法感知到取消请求。
            Thread.currentThread().interrupt();
            return -2;
        }
    }

    /** 持续读取并关闭子进程输出；调用方不需要内容时只排空管道。 */
    private static String read(InputStream input, boolean keepOutput) throws IOException {
        byte[] buffer = new byte[512];
        ByteArrayOutputStream output = keepOutput ? new ByteArrayOutputStream() : null;
        int count;
        while ((count = input.read(buffer)) != -1) {
            // 即使不需要内容也必须持续读取，防止子进程因管道写满而死锁。
            if (output != null) {
                output.write(buffer, 0, count);
            }
        }
        input.close();
        return output == null ? "" : output.toString(StandardCharsets.UTF_8.name());
    }
}
