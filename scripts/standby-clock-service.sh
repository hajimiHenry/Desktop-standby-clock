#!/system/bin/sh

# 开机自启脚本，放在 Magisk 的 /data/adb/service.d/ 目录下由系统以 root 执行。
#
# 为什么不能只靠应用自己的 BOOT_COMPLETED 广播接收器：MIUI 12 有一套私有的
# "自启动"权限（AppOp 10008），每次开机都会被重置成 ignore，哪怕用户之前明确
# 允许过。被 ignore 掉的话，系统压根不会把开机广播发给应用。
#
# Magisk 的 service.d 脚本本身就以 root 运行，所以下面直接启动服务和界面，
# 完全不依赖那个 AppOp，也不需要调用 su（那会弹一个授权提示，很难看）。

package_name="com.henry.standbyclock"
activity_name="${package_name}/.MainActivity"
service_name="${package_name}/.StandbyService"

# 等系统开机完成。service.d 脚本执行得非常早，此时 am、pm 这些命令还不能用。
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done

# 再等应用真正被系统识别。加密存储的设备上，应用可能比系统晚一步才可用。
until pm path "$package_name" >/dev/null 2>&1; do
    sleep 2
done

# 10008 就是 MIUI 那个私有的自启动 AppOp，每次开机都得重新放开。
cmd appops set "$package_name" 10008 allow
# 允许后台运行，防止系统在息屏后把服务冻结掉。
cmd appops set "$package_name" RUN_IN_BACKGROUND allow
cmd appops set "$package_name" RUN_ANY_IN_BACKGROUND allow
# 加入 Doze 白名单，避免深度休眠打断环境光监测和睡眠提醒的计时。
dumpsys deviceidle whitelist +"$package_name" >/dev/null 2>&1

# 取出应用的 uid，然后直接改 Magisk 的策略数据库，关掉 root 授权提示。
# 不关的话，应用每次执行 su（拉回前台时会用到）都会弹一条 toast，
# 在一台专职时钟上非常碍眼。
app_uid="$(cmd package list packages -U "$package_name" | sed -n 's/.*uid://p' | head -n 1)"
if [ -n "$app_uid" ]; then
    magisk --sqlite "UPDATE policies SET notification=0 WHERE uid=$app_uid;"
fi

# 先起后台服务，再起界面。
am start-foreground-service --user 0 -n "$service_name" >/dev/null 2>&1
# 0x34000000 是三个 Intent 标志的组合，含义见 RootShell.bringClockToFront 的注释。
am start --user 0 \
    -a android.intent.action.MAIN \
    -c android.intent.category.LAUNCHER \
    -f 0x34000000 \
    -n "$activity_name" >/dev/null 2>&1

# 写一条系统日志，用 `logcat -s StandbyBoot` 可以确认脚本到底跑没跑。
log -t StandbyBoot "Clock persistence restored for uid=$app_uid"
