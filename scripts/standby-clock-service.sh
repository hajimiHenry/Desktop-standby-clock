#!/system/bin/sh

# MIUI 12 restores its proprietary auto-start AppOp to "ignore" on every boot,
# sometimes even after it was set to allow. Magisk runs service.d scripts as
# root, so the direct service/task start below does not depend on that AppOp,
# invoke `su`, or create a superuser-grant toast.

package_name="com.henry.standbyclock"
activity_name="${package_name}/.MainActivity"
service_name="${package_name}/.StandbyService"

until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done

until pm path "$package_name" >/dev/null 2>&1; do
    sleep 2
done

cmd appops set "$package_name" 10008 allow
cmd appops set "$package_name" RUN_IN_BACKGROUND allow
cmd appops set "$package_name" RUN_ANY_IN_BACKGROUND allow
dumpsys deviceidle whitelist +"$package_name" >/dev/null 2>&1

app_uid="$(cmd package list packages -U "$package_name" | sed -n 's/.*uid://p' | head -n 1)"
if [ -n "$app_uid" ]; then
    magisk --sqlite "UPDATE policies SET notification=0 WHERE uid=$app_uid;"
fi

am start-foreground-service --user 0 -n "$service_name" >/dev/null 2>&1
am start --user 0 \
    -a android.intent.action.MAIN \
    -c android.intent.category.LAUNCHER \
    -f 0x34000000 \
    -n "$activity_name" >/dev/null 2>&1

log -t StandbyBoot "Clock persistence restored for uid=$app_uid"
