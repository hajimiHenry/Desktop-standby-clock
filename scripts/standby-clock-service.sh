#!/system/bin/sh

# 开机自启脚本，放在 Magisk 的 /data/adb/service.d/ 目录下由系统以 root 执行。
#
# 为什么应用自己的 BOOT_COMPLETED 接收器不够：接收器确实能收到广播（这台设备跑的是
# LineageOS 基底的 TrebleDroid GSI，不像 MIUI 那样每次开机重置自启动权限），但
# Android 10 起禁止普通应用从广播接收器里后台拉起 Activity。PersistenceReceiver
# 为此要调 su 执行 am start，而每次调 su 都会弹一条 Magisk 授权提示。
#
# service.d 脚本本身就以 root 运行，直接 am start 即可，既绕开了后台启动限制，
# 也不需要调用 su，专职时钟上不会平白闪一条 toast。

package_name="com.henry.standbyclock"
activity_name="${package_name}/.MainActivity"
service_name="${package_name}/.StandbyService"
magisk_db="/data/adb/magisk.db"

# 等系统开机完成。service.d 脚本执行得非常早，此时 am、pm 这些命令还不能用。
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done

# 再等应用真正被系统识别。加密存储的设备上，应用可能比系统晚一步才可用。
until pm path "$package_name" >/dev/null 2>&1; do
    sleep 2
done

# 允许后台运行，防止系统在息屏后把服务冻结掉。
# 这两个 op 在 AOSP 上默认就是 allow，显式设置只是防御性的保险；
# MIUI 时期还需要设它私有的 AppOp 10008，换到 LineageOS 后那个 op 根本不存在
# （执行会报 Bad operation #10008），所以已经去掉。
cmd appops set "$package_name" RUN_IN_BACKGROUND allow
cmd appops set "$package_name" RUN_ANY_IN_BACKGROUND allow
# 加入 Doze 白名单，避免深度休眠打断环境光监测和睡眠提醒的计时。
dumpsys deviceidle whitelist +"$package_name" >/dev/null 2>&1

# 关掉这个应用的 root 授权提示。应用拉回前台和按 MAC 找吸顶灯时都会执行 su，
# 不关的话每次都弹一条 toast，在一台专职时钟上非常碍眼。
#
# 这里直接改 Magisk 的策略库，而不是用早先的 `magisk --sqlite`：后者在 Magisk 30
# 已被移除（applet 只剩 su 和 resetprop），留着那条命令等于什么都没做。
# 只改 notification 一个字段，policy 授权本身不动。
app_uid="$(cmd package list packages -U "$package_name" | sed -n 's/.*uid://p' | head -n 1)"
if [ -n "$app_uid" ] && [ -x /system/bin/sqlite3 ] && [ -f "$magisk_db" ]; then
    sqlite3 "$magisk_db" "UPDATE policies SET notification=0 WHERE uid=$app_uid;"
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
