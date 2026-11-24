# CLmulator test-build overlay. Sourced by the Android-x86 initrd /init right
# after the system image and /data are mounted, before switch_root.
# Everything we change lives in RAM (tmpfs) or in the data disk: the official
# system.sfs on the ISO is never modified.
. /clm/edition.conf
A=/android
mkdir -p $A/clm
cp -a /clm/. $A/clm/

# 1. build.prop: our model/brand/build id on top of the original file
P=$A/clm/build.prop
grep -v -E "^(ro.product.model|ro.product.brand|ro.product.manufacturer|ro.product.device|ro.build.display.id|ro.build.host|ro.build.user|ro.product.locale.language|ro.product.locale.region|persist.sys.language|persist.sys.country)=" $A/system/build.prop > $P
cat /clm/props >> $P
chmod 644 $P
mount --bind $P $A/system/build.prop

# 2. root: our permissive su over /system/xbin/su (Android < 4.3 only)
if [ "$CLM_SU" = "1" ] && [ -e $A/system/xbin/su ]; then
	chmod 6755 $A/clm/su
	mount --bind $A/clm/su $A/system/xbin/su
fi

# 3. data: boot animation + CLM Center app (copied into the live /data)
mkdir -p $A/data/local $A/data/app
chmod 771 $A/data/local
if [ -e /clm/bootanimation.zip ]; then
	cp /clm/bootanimation.zip $A/data/local/bootanimation.zip
	chmod 644 $A/data/local/bootanimation.zip
fi
cp /clm/CLMCenter.apk $A/data/app/app.clm.center-1.apk
chmod 644 $A/data/app/app.clm.center-1.apk

# 4. late tweaks service (runs inside Android as root after boot)
C=""
grep -q "class_start main" $A/init.rc && C="    class main"
printf '\nservice clmtweak /system/bin/sh /clm/tweak.sh\n%s\n    oneshot\n' "$C" >> $A/init.rc
