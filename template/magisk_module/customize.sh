# shellcheck disable=SC2034
# 安装 Manager UI
ui_print "- Installing Manager UI..."
pm install "$MODPATH/manager.apk" >/dev/null 2>&1
# 即使安装失败也不影响模块整体安装（可能已存在相同或更高版本）
if [ $? -eq 0 ]; then
    ui_print "- Manager UI installed successfully!"
else
    ui_print "- Manager UI already installed or installation failed."
fi

# 删除安装包以节省空间
rm "$MODPATH/manager.apk"

# 设置 Zygisk 模式
SKIPUNZIP=0

FLAVOR=zygisk

enforce_install_from_magisk_app() {
  if $BOOTMODE; then
    ui_print "- Installing from Magisk app"
  else
    ui_print "*********************************************************"
    ui_print "! Install from recovery is NOT supported"
    ui_print "! Recovery sucks"
    ui_print "! Please install from Magisk app"
    abort "*********************************************************"
  fi
}

check_magisk_version() {
  ui_print "- Magisk version: $MAGISK_VER_CODE"
  if [ "$MAGISK_VER_CODE" -lt 24000 ]; then
    ui_print "*********************************************************"
    ui_print "! Please install Magisk v24.0+ (24000+)"
    abort    "*********************************************************"
  fi
}

VERSION=$(grep_prop version "${TMPDIR}/module.prop")
ui_print "- Zygisk version ${VERSION}"

# Extract verify.sh
ui_print "- Extracting verify.sh"
unzip -o "$ZIPFILE" 'verify.sh' -d "$TMPDIR" >&2
if [ ! -f "$TMPDIR/verify.sh" ]; then
  ui_print "*********************************************************"
  ui_print "! Unable to extract verify.sh!"
  ui_print "! This zip may be corrupted, please try downloading again"
  abort    "*********************************************************"
fi
. "$TMPDIR/verify.sh"

extract "$ZIPFILE" 'customize.sh' "$TMPDIR"
extract "$ZIPFILE" 'verify.sh' "$TMPDIR"

check_magisk_version
enforce_install_from_magisk_app

# Check architecture
if [ "$ARCH" != "arm" ] && [ "$ARCH" != "arm64" ] && [ "$ARCH" != "x86" ] && [ "$ARCH" != "x64" ]; then
  abort "! Unsupported platform: $ARCH"
else
  ui_print "- Device platform: $ARCH"
fi

if [ "$API" -lt 27 ]; then
  abort "! Only support SDK 27+ devices"
fi

extract "$ZIPFILE" 'module.prop'        "$MODPATH"
extract "$ZIPFILE" 'service.sh'         "$MODPATH"
extract "$ZIPFILE" 'uninstall.sh'       "$MODPATH"
extract "$ZIPFILE" 'config.json'        "$MODPATH"

ui_print "- Extracting modules folder"
unzip -o "$ZIPFILE" "modules/*" -d "$MODPATH" >&2
# 如果 zip 里真的没东西，确保目录依然存在
mkdir -p "$MODPATH/modules"

ui_print "- Extracting zygisk libraries"

if [ "$FLAVOR" == "zygisk" ]; then
  mkdir -p "$MODPATH/zygisk"
  if [ "$ARCH" = "arm" ] || [ "$ARCH" = "arm64" ]; then
    extract "$ZIPFILE" "zygisk/armeabi-v7a.so" "$MODPATH"

    if [ "$IS64BIT" = true ]; then
      extract "$ZIPFILE" "zygisk/arm64-v8a.so" "$MODPATH"
    fi
  fi

  if [ "$ARCH" = "x86" ] || [ "$ARCH" = "x64" ]; then
    extract "$ZIPFILE" "zygisk/x86.so" "$MODPATH"

    if [ "$IS64BIT" = true ]; then
      extract "$ZIPFILE" "zygisk/x86_64.so" "$MODPATH"
    fi
  fi
fi


set_perm_recursive "$MODPATH" 0 0 0755 0644

ui_print "Zygisk module installed successfully!"
