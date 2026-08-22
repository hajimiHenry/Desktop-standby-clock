#!/system/bin/sh

# 电池守护脚本：让长期插电当时钟的老手机远离高电量、高电压状态。
#
# 要解决的问题：这台手机 24 小时插着电，如果不管，电量会一直卡在 100%。对这块
# 已经跑了 1200+ 循环、实际容量只剩设计值 84% 的老电池来说，满电意味着电芯长期
# 被顶在 4.37V（voltage_max 是 4.48V 的高压电池）。高压 + 常年不下来会加速电解液
# 氧化产气，先鼓包，极端情况才是热失控——这是这台机器最现实的安全风险。
#
# 做法是把电量压在 30%~50% 之间循环。选这个区间是为了安全而不是为了省电池寿命：
# 50% 对应约 3.8V，30% 对应约 3.7V，比满电低了 0.5V 以上，产气速率差一个数量级。
# 这台机器不出门，用不着留续航余量，所以上限敢压到 50%。
#
# 这台设备的内核在禁用充电时会直接切断充电输入（usb/online 会变 0），所以
# "禁用充电"就等于让它靠电池放电，电量因此能真的降下来。
#
# 需要 root（要写 sysfs 节点），通过 Magisk 的 service.d 开机自启。

# sysfs 节点：控制充电开关、读电量、读温度、读健康状态。
# 路径是这台设备特有的，换机型很可能不一样。
charge_node="/sys/class/power_supply/battery/battery_charging_enabled"
capacity_node="/sys/class/power_supply/battery/capacity"
temperature_node="/sys/class/power_supply/battery/temp"
health_node="/sys/class/power_supply/battery/health"
voltage_node="/sys/class/power_supply/battery/voltage_now"
# 用创建目录来实现互斥锁（mkdir 是原子操作），防止脚本被启动两份互相打架。
lock_dir="/dev/.battery-guardian.lock"
# 长期状态记录。logcat 是环形缓冲、重启就没，想事后回看"这几天最高烧到多少度"
# 得有个落盘的地方。
history_file="/data/adb/battery-guardian.log"

# 电量下限，降到这里就恢复充电。
lower_percent=30
# 电量上限，充到这里就断充电。
upper_percent=50
# 兜底下限：低于这个电量时无条件恢复充电，温度和 health 都不再拦。
# 为什么必须有这条：温度判断的优先级在电量之上，夏天房间热 + 屏幕常亮时电池
# 可能长期停在阈值以上，那样会一路放电到关机。老电池深度放电本身也是风险，
# 而且"永远充不进电的手机"是这个脚本最不该造成的结果。
critical_percent=20
# 温度阈值，单位是 0.1 摄氏度：42.0℃ 以上强制停充。
hot_temperature=420
# 降到 38.0℃ 才允许恢复。留 4 度的迟滞区间，避免在阈值附近反复开关充电。
cool_temperature=380
poll_seconds=30
# 每 120 轮（约 1 小时）往历史文件写一行快照。
snapshot_rounds=120

tag="BatteryGuardian"
# 状态机的当前状态：charging（正在充）/ soc_hold（因电量到顶而停充）/
# thermal_hold（因过热而停充）/ health_hold（因电池健康状态异常而停充）。
# 区分它们是必要的，因为退出条件各不相同。
state="charging"
# 观察到的最高温度，只用于记录，方便事后判断温度阈值定得合不合适。
peak_temperature=0
snapshot_countdown=0

write_log() {
    log -p i -t "$tag" "$*"
}

# 往落盘的历史文件追加一行，顺带做最朴素的轮转，免得长期跑把 /data 撑大。
write_history() {
    size="$(wc -c < "$history_file" 2>/dev/null)"
    case "$size" in
        ''|*[!0-9]*) size=0 ;;
    esac
    if [ "$size" -gt 204800 ]; then
        mv "$history_file" "$history_file.1" 2>/dev/null
    fi
    echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$history_file" 2>/dev/null
}

# 设置充电开关。desired 传 1 开、0 关；reason 只用于日志，方便事后追查。
set_charging() {
    desired="$1"
    reason="$2"
    current="$(cat "$charge_node" 2>/dev/null)"

    # 只在状态确实需要改变时才写，避免每 30 秒刷一条重复日志。
    if [ "$current" != "$desired" ]; then
        if echo "$desired" > "$charge_node" 2>/dev/null; then
            write_log "charging_enabled=$desired reason=$reason"
            write_history "charging_enabled=$desired reason=$reason"
        else
            write_log "failed to set charging_enabled=$desired reason=$reason"
            write_history "FAILED to set charging_enabled=$desired reason=$reason"
            return 1
        fi
    fi
    return 0
}

# 退出前的收尾：务必把充电恢复成开启状态，再清掉锁。
# 这是最关键的安全保障——脚本无论因为什么原因退出，都绝不能留下一台
# 永远充不进电的手机。
restore_and_exit() {
    # 先摘掉信号处理，防止收尾过程中又收到信号导致重入。
    trap - HUP INT TERM
    set_charging 1 "guardian_exit"
    rmdir "$lock_dir" 2>/dev/null
    write_log "stopped; OEM charging restored"
    write_history "stopped; OEM charging restored"
    exit 0
}

# 等系统真正开机完成。开机早期 sysfs 节点可能还没就绪。
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done

# 抢锁。mkdir 在已存在时会失败，以此实现原子的互斥判断。
if ! mkdir "$lock_dir" 2>/dev/null; then
    write_log "another instance is already running"
    exit 0
fi

# 装好信号处理，被 kill 时也能走正常收尾流程。
trap restore_and_exit HUP INT TERM

# 节点不可用（换了机型、内核不支持）时干脆退出，并保持系统原本的充电行为。
# 宁可不省电池，也不能因为读不到电量而把充电胡乱关掉。
if [ ! -w "$charge_node" ] || [ ! -r "$capacity_node" ] || [ ! -r "$temperature_node" ]; then
    write_log "required battery sysfs nodes are unavailable; OEM charging left enabled"
    restore_and_exit
fi

# 从厂商默认的"正常充电"状态起步，后面再根据实测电量决定要不要断。
set_charging 1 "guardian_start"
write_log "started lower=$lower_percent upper=$upper_percent critical=$critical_percent hot=$hot_temperature cool=$cool_temperature"
# 开机时把电池的"体检指标"记一笔：满充容量掉得快、循环数涨得猛，都是该换电池的信号。
write_history "started lower=$lower_percent upper=$upper_percent critical=$critical_percent \
charge_full=$(cat /sys/class/power_supply/battery/charge_full 2>/dev/null) \
design=$(cat /sys/class/power_supply/battery/charge_full_design 2>/dev/null) \
cycles=$(cat /sys/class/power_supply/battery/cycle_count 2>/dev/null)"

while true; do
    capacity="$(cat "$capacity_node" 2>/dev/null)"
    temperature="$(cat "$temperature_node" 2>/dev/null)"
    health="$(cat "$health_node" 2>/dev/null)"

    # 校验两个读数都是纯数字且非空。三个模式分别匹配：含有非数字字符、
    # 电量为空（冒号打头）、温度为空（冒号结尾）。
    # 读数异常时一律恢复充电——判断依据都不可靠了，就别再擅自断电。
    case "$capacity:$temperature" in
        *[!0-9:]*|:*|*:)
            set_charging 1 "invalid_sensor_reading"
            state="charging"
            write_log "invalid sensor reading capacity=$capacity temperature=$temperature"
            sleep "$poll_seconds"
            continue
            ;;
    esac

    [ "$temperature" -gt "$peak_temperature" ] && peak_temperature="$temperature"

    # 决策优先级从上到下：兜底电量 > 健康状态 > 温度 > 电量上限 > 电量下限 > 维持现状。

    # 兜底电量排在最前面：宁可在不理想的条件下充一点电，也不能放到关机。
    if [ "$capacity" -le "$critical_percent" ]; then
        set_charging 1 "critical_override_${capacity}_health_${health}_temp_${temperature}"
        state="charging"
        write_history "critical override capacity=$capacity health=$health temp=$temperature"
    # 驱动报告电池健康异常（过热、过压、低温、故障）时停充。
    # Good / Warm / Cool 都是 JEITA 的正常档位，不算异常。
    elif [ "$health" != "Good" ] && [ "$health" != "Warm" ] && [ "$health" != "Cool" ]; then
        set_charging 0 "health_${health}"
        if [ "$state" != "health_hold" ]; then
            write_history "battery health abnormal: $health capacity=$capacity temp=$temperature \
voltage=$(cat "$voltage_node" 2>/dev/null)"
        fi
        state="health_hold"
    # 过热：无条件停充。
    elif [ "$temperature" -ge "$hot_temperature" ]; then
        set_charging 0 "temperature_${temperature}"
        state="thermal_hold"
    # 正处于过热停充状态，看能不能解除。
    elif [ "$state" = "thermal_hold" ]; then
        if [ "$temperature" -le "$cool_temperature" ]; then
            # 温度降下来了，但还得看电量：如果这期间电量已经到顶了，
            # 就直接转入电量停充状态，不用白白充一下再断。
            if [ "$capacity" -ge "$upper_percent" ]; then
                set_charging 0 "cooled_at_upper_${capacity}"
                state="soc_hold"
            else
                set_charging 1 "temperature_recovered_${temperature}"
                state="charging"
            fi
        else
            # 还在迟滞区间里，继续保持停充。
            set_charging 0 "thermal_hysteresis_${temperature}"
        fi
    # 电量到上限，停充，开始放电。
    elif [ "$capacity" -ge "$upper_percent" ]; then
        set_charging 0 "upper_threshold_${capacity}"
        state="soc_hold"
    # 电量到下限，恢复充电。
    elif [ "$capacity" -le "$lower_percent" ]; then
        set_charging 1 "lower_threshold_${capacity}"
        state="charging"
    # 电量在下限和上限之间：保持当前方向不变，这样才能形成完整的充放循环。
    # 如果这里改成"中间就充电"，电量会一直卡在上限附近反复开关，失去意义。
    elif [ "$state" = "charging" ]; then
        set_charging 1 "charging_to_upper_${capacity}"
    else
        set_charging 0 "discharging_to_lower_${capacity}"
        state="soc_hold"
    fi

    # 定期落盘一行快照，长期观察温度和电压走势用。
    if [ "$snapshot_countdown" -le 0 ]; then
        write_history "state=$state capacity=$capacity temp=$temperature peak=$peak_temperature \
health=$health voltage=$(cat "$voltage_node" 2>/dev/null) charging=$(cat "$charge_node" 2>/dev/null)"
        snapshot_countdown="$snapshot_rounds"
    else
        snapshot_countdown=$((snapshot_countdown - 1))
    fi

    sleep "$poll_seconds"
done
