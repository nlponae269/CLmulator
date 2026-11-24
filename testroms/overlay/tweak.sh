#!/system/bin/sh
# CLmulator test-build: runs once per boot as root, after Android has started.
. /clm/edition.conf
n=0
while [ "`getprop sys.boot_completed`" != "1" ] && [ "`getprop dev.bootcomplete`" != "1" ]; do
	sleep 2
	n=`expr $n + 1`
	[ $n -gt 300 ] && break
done
sleep 4
setprop clm.tweak.started 1

# screen: 30 minutes instead of the default 1 minute
if [ -x /system/bin/settings ]; then
	settings put system screen_off_timeout 1800000
else
	sqlite3 /data/data/com.android.providers.settings/databases/settings.db \
		"update system set value='1800000' where name='screen_off_timeout';" 2>/dev/null
fi
svc power stayon true 2>/dev/null

# wallpaper: only once, so the user can change it later
if [ ! -e /data/local/.clm_wallpaper ] && [ -e /clm/wallpaper.jpg ]; then
	SDK=`getprop ro.build.version.sdk`
	if [ "$SDK" -ge 17 ]; then W=/data/system/users/0/wallpaper
	elif [ "$SDK" -ge 5 ]; then W=/data/system/wallpaper
	else W=/data/data/com.android.settings/files/wallpaper; fi
	D=`dirname $W`
	n=0
	while [ ! -d $D ] && [ $n -lt 30 ]; do sleep 1; n=`expr $n + 1`; done
	cat /clm/wallpaper.jpg > $W
	chown system.system $W 2>/dev/null || chown 1000:1000 $W
	chmod 666 $W
	echo 1 > /data/local/.clm_wallpaper
fi
setprop clm.tweak.done 1
