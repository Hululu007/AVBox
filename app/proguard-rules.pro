#############################################
#
# 对于一些基本指令的添加
#
#############################################
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontskipnonpubliclibraryclassmembers
-dontpreverify
-verbose
-printmapping proguardMapping.txt
-optimizations !code/simplification/cast,!field/*,!class/merging/*
-keepattributes *Annotation*,InnerClasses
-keepattributes EnclosingMethod, InnerClasses
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes LineNumberTable
-renamesourcefileattribute SourceFile

# 将包里的类混淆成n个再重新打包到一个统一的package中  会覆盖flattenpackagehierarchy选项
# (AGP 9 的 R8 已忽略 -useuniqueclassmembernames;-flattenpackagehierarchy 与其冲突,均已移除)
#############################################
#
# Android开发中一些需要保留的公共部分
#
#############################################

# 保留我们使用的四大组件，自定义的Application等等这些类不被混淆
# 因为这些子类都有可能被外部调用
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application.**
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference
-keep public class * extends android.view.View
-keep public class com.android.vending.licensing.ILicensingService.**

-dontwarn androidx.**
-keep class androidx.** { *; }
-keep interface androidx.** { *; }

-keep class org.xmlpull.v1.** {*;}

-dontwarn org.fourthline.cling.**
-keep class org.fourthline.cling.** { *; }

# 保留R下面的资源
-keep class **.R$* {*;}

# 保留本地native方法不被混淆
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保留在Activity中的方法参数是view的方法，
# 这样以来我们在layout中写的onClick就不会被影响
-keepclassmembers class * extends android.app.Activity{
    public void *(android.view.View);
}

# 保留枚举类不被混淆
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保留我们自定义控件（继承自View）不被混淆
-keep public class * extends android.view.View{
    *** get*();
    void set*(***);
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keep public class * extends androidx.recyclerview.widget.RecyclerView$LayoutManager{
    *** get*();
    void set*(***);
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# 保留Parcelable序列化类不被混淆
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# 保留Serializable序列化的类不被混淆
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    !private <fields>;
    !private <methods>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# 对于带有回调函数的onXXEvent、**On*Listener的，不能被混淆
-keepclassmembers class * {
    void *(**On*Event);
    void *(**On*Listener);
}
-dontwarn android.view.**
-dontwarn android.media.**
#okhttp
-dontwarn okhttp3.**
-keep class okhttp3.**{*;}
#okio
-dontwarn okio.**
-keep class okio.**{*;}
#gson
# Gson specific classes
-dontwarn sun.misc.**
#-keep class com.google.gson.stream.** { *; }
# Application classes that will be serialized/deserialized over Gson
# Prevent proguard from stripping interface information from TypeAdapter, TypeAdapterFactory,
# JsonSerializer, JsonDeserializer instances (so they can be used in @JsonAdapter)
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
# Prevent R8 from leaving Data object members always null
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}
# Gson 通过字段名反序列化源站首页分类和影片列表(已被下方 bean 全量 keep 覆盖,仅保留引擎侧规则)
-keep,allowobfuscation,allowoptimization class * extends com.google.gson.reflect.TypeToken
#xstream
-keep class com.thoughtworks.xstream.converters.extended.SubjectConverter { *; }
-keep class com.thoughtworks.xstream.converters.extended.ThrowableConverter { *; }
-keep class com.thoughtworks.xstream.converters.extended.StackTraceElementConverter { *; }
-keep class com.thoughtworks.xstream.converters.extended.CurrencyConverter { *; }
-keep class com.thoughtworks.xstream.converters.extended.RegexPatternConverter { *; }
-keep class com.thoughtworks.xstream.converters.extended.CharsetConverter { *; }
-keep class com.thoughtworks.xstream.** { *; }
#eventbus
-keepclassmembers class * {
    @org.greenrobot.eventbus.Subscribe <methods>;
}
-keep enum org.greenrobot.eventbus.ThreadMode { *; }
# And if you use AsyncExecutor:
-keepclassmembers class * extends org.greenrobot.eventbus.util.ThrowableFailureEvent {
    <init>(java.lang.Throwable);
}

# 以下 app/catvod 专属 keep 已由文件末尾的两条全量 keep 覆盖,不再重复
# 迅雷下载模块
-keep class com.xunlei.downloadlib.** {*;}
# quickjs引擎
#-keep class com.github.tvbox.quickjs.** {*;}
-keep class com.whl.quickjs.** {*;}
# IjkPlayer(player 模块内含 ijk 源码)
-keep class tv.danmaku.ijk.** { *; }
-dontwarn tv.danmaku.ijk.**

# media3(含 jellyfin ffmpeg 软解,类都在 androidx.media3 包下)
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# 实体类(Gson 按字段名反射赋值,字段/类名必须保留,否则 JSON 解析后为空)
-keep class com.github.tvbox.osc.bean.** { *; }

# 支持影视的ali相关的jar
-keep class com.google.gson.**{*;}
# Zxing
-keep class com.google.zxing.**{*;}
# 动态加载的爬虫 jar 运行期契约(spec §6.3):jar 由 DexClassLoader 加载,按「宿主类名」解析,
# R8 一旦改名或裁剪掉这些类,jar 就会 NoClassDefFoundError —— debug 不混淆,只有 release 会暴露。
# ⚠️ 不要在这里补 org.slf4j 的 keep、也不要给宿主加 slf4j 依赖(jar 自己 shade 了 slf4j,
#    宿主提供绑定反而会让它抛 IncompatibleClassChangeError,详见 spec §6.3)
# Guava:由 media3-common 传递带入,宿主代码未静态引用 → R8 默认改名/裁剪,jar 引用即崩
-keep class com.google.common.** { *; }
# Sardine(WebDAV):订阅源 jar 的 com.github.catvod.spider.WebDAV 会 new OkHttpSardine()
# 做 WebDAV 备份/还原,宿主零静态引用 → 不 keep 就整包被 R8 裁掉
-keep class com.thegrizzlylabs.sardineandroid.** { *; }
# Kotlin 标准库:jar 直接调用 kotlin.*(如 merge/e0/a 调 kotlin.io.TextStreamsKt),
# 而宿主 Compose 只用到 stdlib 的一部分 → 其余被 R8 裁掉(debug 有、release 无),
# 与 Guava 属同一类问题。对齐上游 fongmi 的同名规则。
-keeppackagenames kotlin.**
-keep class kotlin.** { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
# MaterialKolor PaletteStyle(主题设置页):枚举名被持久化到 KV,
# 上面的通用枚举规则只保留 valueOf/values 方法签名、不保留常量字段名,
# 重命名后 PaletteStyle.valueOf(持久化名) 会抛 IllegalArgumentException(主题风格回落默认值)
-keepclassmembers enum com.materialkolor.PaletteStyle {
    <fields>;
}
# Nano
-keep class fi.iki.elonen.** { *; }

# Python支持(Chaquopy 从 Python 侧按类名调用 Java 桥接类,必须保留)
-keep public class com.undcover.freedom.pyramid.** { *; }
-dontwarn com.undcover.freedom.pyramid.**
-keep public class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# AGP 9 / R8:app 业务代码与源加载桥接全部保留。
# Jar/JS/Python 三类采集源在运行时按类名调用 app 类,Gson 按字段名反射赋值,
# 混淆改名会导致详情/播放流程拿不到数据而退回主页。第三方库仍正常混淆与删减。
-keep class com.github.tvbox.osc.** { *; }
-keep class com.github.catvod.** { *; }

# AGP 9 / R8:cling(seamless)与 xstream 引用的桌面端可选类在 Android 上不存在,按官方生成的 missing_rules.txt 追加
-dontwarn com.bea.xml.stream.MXParserFactory
-dontwarn com.bea.xml.stream.XMLOutputFactoryBase
-dontwarn com.ctc.wstx.stax.WstxInputFactory
-dontwarn com.ctc.wstx.stax.WstxOutputFactory
-dontwarn java.awt.BorderLayout
-dontwarn java.awt.Color
-dontwarn java.awt.Component
-dontwarn java.awt.Container
-dontwarn java.awt.Dimension
-dontwarn java.awt.Font
-dontwarn java.awt.LayoutManager
-dontwarn java.awt.Rectangle
-dontwarn java.awt.Toolkit
-dontwarn java.awt.Window
-dontwarn java.awt.datatransfer.Clipboard
-dontwarn java.awt.datatransfer.ClipboardOwner
-dontwarn java.awt.datatransfer.StringSelection
-dontwarn java.awt.datatransfer.Transferable
-dontwarn java.awt.event.ActionEvent
-dontwarn java.awt.event.ActionListener
-dontwarn java.awt.event.ItemListener
-dontwarn java.awt.event.WindowEvent
-dontwarn java.awt.event.WindowListener
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn java.beans.PropertyEditor
-dontwarn javax.activation.ActivationDataFlavor
-dontwarn javax.swing.AbstractAction
-dontwarn javax.swing.AbstractButton
-dontwarn javax.swing.BorderFactory
-dontwarn javax.swing.Box
-dontwarn javax.swing.BoxLayout
-dontwarn javax.swing.Icon
-dontwarn javax.swing.ImageIcon
-dontwarn javax.swing.JButton
-dontwarn javax.swing.JCheckBox
-dontwarn javax.swing.JComboBox
-dontwarn javax.swing.JDialog
-dontwarn javax.swing.JFrame
-dontwarn javax.swing.JLabel
-dontwarn javax.swing.JPanel
-dontwarn javax.swing.JScrollPane
-dontwarn javax.swing.JTable
-dontwarn javax.swing.JToolBar
-dontwarn javax.swing.ListSelectionModel
-dontwarn javax.swing.SwingUtilities
-dontwarn javax.swing.border.Border
-dontwarn javax.swing.border.TitledBorder
-dontwarn javax.swing.event.ListSelectionListener
-dontwarn javax.swing.plaf.FontUIResource
-dontwarn javax.swing.table.AbstractTableModel
-dontwarn javax.swing.table.DefaultTableCellRenderer
-dontwarn javax.swing.table.JTableHeader
-dontwarn javax.swing.table.TableCellRenderer
-dontwarn javax.swing.table.TableColumn
-dontwarn javax.swing.table.TableColumnModel
-dontwarn javax.swing.table.TableModel
-dontwarn javax.xml.bind.DatatypeConverter
-dontwarn javax.xml.stream.Location
-dontwarn javax.xml.stream.XMLInputFactory
-dontwarn javax.xml.stream.XMLOutputFactory
-dontwarn javax.xml.stream.XMLStreamException
-dontwarn javax.xml.stream.XMLStreamReader
-dontwarn javax.xml.stream.XMLStreamWriter
-dontwarn net.sf.cglib.proxy.Callback
-dontwarn net.sf.cglib.proxy.CallbackFilter
-dontwarn net.sf.cglib.proxy.Enhancer
-dontwarn net.sf.cglib.proxy.Factory
-dontwarn net.sf.cglib.proxy.NoOp
-dontwarn net.sf.cglib.proxy.Proxy
-dontwarn nu.xom.Attribute
-dontwarn nu.xom.Builder
-dontwarn nu.xom.Document
-dontwarn nu.xom.Element
-dontwarn nu.xom.Elements
-dontwarn nu.xom.Node
-dontwarn nu.xom.ParentNode
-dontwarn nu.xom.ParsingException
-dontwarn nu.xom.Text
-dontwarn nu.xom.ValidityException
-dontwarn org.codehaus.jettison.AbstractXMLStreamWriter
-dontwarn org.codehaus.jettison.mapped.Configuration
-dontwarn org.codehaus.jettison.mapped.MappedNamespaceConvention
-dontwarn org.codehaus.jettison.mapped.MappedXMLInputFactory
-dontwarn org.codehaus.jettison.mapped.MappedXMLOutputFactory
-dontwarn org.dom4j.Attribute
-dontwarn org.dom4j.Branch
-dontwarn org.dom4j.Document
-dontwarn org.dom4j.DocumentException
-dontwarn org.dom4j.DocumentFactory
-dontwarn org.dom4j.Element
-dontwarn org.dom4j.io.OutputFormat
-dontwarn org.dom4j.io.SAXReader
-dontwarn org.dom4j.io.XMLWriter
-dontwarn org.dom4j.tree.DefaultElement
-dontwarn org.jdom.Attribute
-dontwarn org.jdom.Content
-dontwarn org.jdom.DefaultJDOMFactory
-dontwarn org.jdom.Document
-dontwarn org.jdom.Element
-dontwarn org.jdom.JDOMException
-dontwarn org.jdom.JDOMFactory
-dontwarn org.jdom.Text
-dontwarn org.jdom.input.SAXBuilder
-dontwarn org.jdom2.Attribute
-dontwarn org.jdom2.Content
-dontwarn org.jdom2.DefaultJDOMFactory
-dontwarn org.jdom2.Document
-dontwarn org.jdom2.Element
-dontwarn org.jdom2.JDOMException
-dontwarn org.jdom2.JDOMFactory
-dontwarn org.jdom2.Text
-dontwarn org.jdom2.input.SAXBuilder
-dontwarn org.joda.time.DateTime
-dontwarn org.joda.time.DateTimeZone
-dontwarn org.joda.time.format.DateTimeFormatter
-dontwarn org.joda.time.format.ISODateTimeFormat
-dontwarn org.kxml2.io.KXmlParser
-dontwarn org.xmlpull.mxp1.MXParser

# 液态玻璃导航栏(io.github.kyant0 backdrop/capsule):库内部按 API 版本条件引用高版本 API,R8 缺类告警放行
-dontwarn com.kyant.backdrop.**
-dontwarn com.kyant.capsule.**
-dontwarn com.kyant.shapes.**

#############################################
# release 剥离全部日志(2026-09-14)
# release 已 isMinifyEnabled=true(R8),这里声明日志方法无副作用:
# R8 会把调用点整条删除(连带参数里的字符串拼接一起消失,不只是不输出),
# 因此 release 包里**调用点与日志字符串全部消失**(2026-09-19 用 dexdump 逐 dex 复验:
# `echo-*` 埋点串 0 命中、`LOG;->i(` 与 `android/util/Log;->i(` 调用点均 0 处;
# 连"仅为日志而取"的实参调用如 `view.currentPosition()` 也一并消失)。
# ⚠️ 但"残留"并非为零:`com.github.tvbox.osc.util.LOG` 类本体(含 `fileLog` 方法体与
# `FILE_LOG_PREFIXES` 的 15 个前缀字面量)仍留在 dex 里 —— 其静态初始化器把这些字符串
# load 出来再 sput,R8 未判定为纯死代码。属**不可达的死数据,无执行影响**;
# 若要清干净,应在 `LOG.java` 里让 FILE_LOG=false 时把前缀数组一并折叠,而不是改本文件。
# 覆盖三条通道:
#   1) android.util.Log —— 全工程 337 处直接调用
#   2) com.github.catvod.crawler.SpiderDebug —— 外挂 jar 的诊断通道,内部就是 Log.d
#   3) com.github.tvbox.osc.util.LOG —— 本应用封装(含落盘 files/preload_debug.log 的排查通道)
#############################################
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
    public static *** println(...);
}

-assumenosideeffects class com.github.catvod.crawler.SpiderDebug {
    public static *** log(...);
}

-assumenosideeffects class com.github.tvbox.osc.util.LOG {
    public static *** d(...);
    public static *** i(...);
    public static *** e(...);
    public static *** longI(...);
    public static *** longE(...);
}
