#!/system/bin/sh

# 电池守护脚本：让长期插电当时钟的手机远离高电量状态。
#
# 要解决的问题：锂电池长期保持满电（尤其还伴随发热）会加速老化鼓包。这台手机
# 24 小时插着电，如果不管，电量会一直卡在 100%，几个月就把电池废了。
#
# 做法是把电量控制在 40%~70% 之间来回循环。这台设备的内核在禁用充电时会直接
# 切断充电输入，所以"禁用充电"就等于让它靠电池放电，电量因此能真的降下来。
#
# 需要 root（要写 sysfs 节点），通过 Magisk 的 service.d 开机自启。

# 三个 sysfs 节点：控制充电开关、读电量百分比、读电池温度。
# 路径是这台设备特有的，换机型很可能不一样。
charge_node="/sys/class/power_supply/battery/battery_charging_enabled"
capacity_node="/sys/class/power_supply/battery/capacity"
temperature_node="/sys/class/power_supply/battery/temp"
# 用创建目录来实现互斥锁（mkdir 是原子操作），防止脚本被启动两份互相打架。
lock_dir="/dev/.battery-guardian.lock"

# 电量下限，降到这里就恢复充电。
lower_percent=40
# 电量上限，充到这里就断充电。
upper_percent=70
# 温度阈值，单位是 0.1 摄氏度：42.0℃ 以上强制停充。
hot_temperature=420
# 降到 38.0℃ 才允许恢复。留 4 度的迟滞区间，避免在阈值附近反复开关充电。
cool_temperature=380
poll_seconds=30

tag="BatteryGuardian"
# 状态机的当前状态：charging（正在充）/ soc_hold（因电量到顶而停充）/
# thermal_hold（因过热而停充）。区分后两者是必要的，因为退出条件不同。
state="charging"

write_log() {
    log -p i -t "$tag" "$*"
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
        else
            write_log "failed to set charging_enabled=$desired reason=$reason"
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
write_log "started lower=$lower_percent upper=$upper_percent hot=$hot_temperature cool=$cool_temperature"

while true; do
    capacity="$(cat "$capacity_node" 2>/dev/null)"
    temperature="$(cat "$temperature_node" 2>/dev/null)"

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

    # 决策优先级从上到下：温度 > 电量上限 > 电量下限 > 维持现状。
    # 温度排第一是因为过热对电池的损害比高电量更直接。

    # 过热：无条件停充。
    if [ "$temperature" -ge "$hot_temperature" ]; then
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
            # 还在 38~42 度这段迟滞区间里，继续保持停充。
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
    # 电量在 40~70 中间地带：保持当前方向不变，这样才能形成完整的充放循环。
    # 如果这里改成"中间就充电"，电量会一直卡在 70 附近反复开关，失去意义。
    elif [ "$state" = "charging" ]; then
        set_charging 1 "charging_to_upper_${capacity}"
    else
        set_charging 0 "discharging_to_lower_${capacity}"
        state="soc_hold"
    fi

    sleep "$poll_seconds"
done
