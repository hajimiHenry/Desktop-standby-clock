package com.henry.standbyclock;

import java.io.IOException;
import java.io.InputStream;

/**
 * 以 root 身份执行 shell 命令的极简封装。
 *
 * <p>用途只有一个：这台手机是专职的桌面时钟，被系统或用户误划掉后要能自己回到前台。
 * 普通应用没权限把自己拉回前台（Android 10 起限制后台启动 Activity），
 * 所以借助已 root 的设备执行 `am start` 绕过。设备没 root 时 `su` 直接失败，
 * 调用方会退回到普通的 startActivity 兜底（见 PersistenceReceiver）。
 */
final class RootShell {
    private RootShell() {}

    /**
     * 执行一条 root 命令并等它跑完。
     *
     * @return 进程退出码，0 表示成功
     */
    static int run(String command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start();
        // 必须把输出读干净再 waitFor：管道缓冲区写满后子进程会阻塞，
        // 那样 waitFor 就会永远等下去（经典的死锁坑）。
        drain(process.getInputStream());
        return process.waitFor();
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

    /** 读空并关闭子进程的输出流，内容丢弃即可。 */
    private static void drain(InputStream input) throws IOException {
        byte[] buffer = new byte[512];
        while (input.read(buffer) != -1) {
            // 本应用用到的 root 命令没有需要展示给用户的输出，读掉是为了防阻塞。
        }
        input.close();
    }
}
