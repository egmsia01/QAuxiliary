package cc.microblock.hook

import android.view.View
import android.widget.RelativeLayout
import cc.hicore.QApp.QAppUtils
import cc.ioctl.util.HookUtils
import cc.ioctl.util.Reflex
import com.github.kyuubiran.ezxhelper.utils.ArgTypes
import com.github.kyuubiran.ezxhelper.utils.Args
import com.github.kyuubiran.ezxhelper.utils.getObjectAs
import com.github.kyuubiran.ezxhelper.utils.hookAllConstructorAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.github.kyuubiran.ezxhelper.utils.hookReplace
import com.github.kyuubiran.ezxhelper.utils.newInstance
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import xyz.nextalone.util.get
import xyz.nextalone.util.invoke
import xyz.nextalone.util.method
import xyz.nextalone.util.set

abstract class ExtraEmoticon {
//    abstract fun emoticonId(): String
//    abstract fun emoticonName(): String
    abstract fun QQEmoticonObject(): Any
}

abstract class ExtraEmoticonPanel {
    abstract fun emoticons(): List<ExtraEmoticon>
    abstract fun emoticonPanelIconURL(): String
    abstract fun uniqueId(): String;
}

abstract class ExtraEmoticonProvider {
    abstract fun extraEmoticonList(): List<ExtraEmoticonPanel>
    abstract fun uniqueId(): String;
}

fun listDir (dir: String): List<String> {
    val list = mutableListOf<String>();
    val file = java.io.File(dir);
    if(!file.exists()) return list;
    if(!file.isDirectory) return list;
    for(f in file.listFiles()) {
        list.add(f.absolutePath);
    }
    return list
}

class LocalDocumentEmoticonProvider : ExtraEmoticonProvider() {
    class Panel(path: String) : ExtraEmoticonPanel() {
        var emoticons: List<ExtraEmoticon>;
        var iconPath: String? = null;
        init {
            val files = listDir(path);
            val emoticons = mutableListOf<ExtraEmoticon>();
            val FavoriteEmoticonInfo = Initiator.loadClass("com.tencent.mobileqq.emoticonview.FavoriteEmoticonInfo");
            for (file in files) {
                val filename = file.substring(file.lastIndexOf("/") + 1);
                if(filename.startsWith("__cover__.")) {
                    iconPath = file;
                    continue;
                }

                emoticons.add(object : ExtraEmoticon() {
                    override fun QQEmoticonObject(): Any {
                        val info = FavoriteEmoticonInfo.newInstance();
                        info.set("path", file);
                        return info;
                    }
                })
            }
            this.emoticons = emoticons
            if (iconPath == null && files.size > 0) {
                iconPath = files[0];
            }
        }
        override fun emoticons(): List<ExtraEmoticon> {
            return emoticons;
        }

        override fun emoticonPanelIconURL(): String {
            return "file://$iconPath";
        }

        override fun uniqueId(): String {
            return iconPath!!;
        }
    }
    override fun extraEmoticonList(): List<ExtraEmoticonPanel> {
        val files = listDir("/storage/emulated/0/Documents/TGStickersExported");
        val panels = mutableListOf<ExtraEmoticonPanel>()
        for (file in files) {
            val panel = Panel(file);
            panels.add(panel);
        }
        return panels;
    }
    override fun uniqueId(): String {
        return "LocalDocumentEmoticonProvider";
    }
}


@FunctionHookEntry
@UiItemAgentEntry
object DumpTelegramStickers : CommonSwitchFunctionHook() {
    override val name = "使用 Telegram Stickers 表情包集"
    override val description = "【还没做完】需使用 MicroBlock 的 Telegram 表情包同步插件\n\n可能需要重启 QQ";

    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.CHAT_CATEGORY;

    override fun initOnce(): Boolean {
        val EmoticonPanelController = Initiator.loadClass("com.tencent.mobileqq.emoticonview.EmoticonPanelController");
        val EmotionPanelViewPagerAdapter = Initiator.loadClass("com.tencent.mobileqq.emoticonview.EmotionPanelViewPagerAdapter");
        val EmoticonTabAdapter = Initiator.loadClass("com.tencent.mobileqq.emoticonview.EmoticonTabAdapter");
        val EmoticonPanelInfo = Initiator.loadClass("com.tencent.mobileqq.emoticonview.EmotionPanelInfo");
        val EmoticonPackage = Initiator.loadClass("com.tencent.mobileqq.data.EmoticonPackage");

        var providers: List<ExtraEmoticonProvider> = listOf(LocalDocumentEmoticonProvider());

        class QAEpId {
            public var providerId: String = "";
            public var panelId: String = "";
        }
        fun parseQAEpId(epId: String): QAEpId? {
            if (!epId.startsWith("qa:")) return null;
            val data = epId.substring(3);
            val providerId = data.substring(0, data.indexOf(":"));
            val panelId = data.substring(data.indexOf(":") + 1);
            return QAEpId().apply {
                this.providerId = providerId;
                this.panelId = panelId;
            }
        }

        var emoticonPanelViewAdapterInstance: Any? = null;

        EmotionPanelViewPagerAdapter.hookAllConstructorAfter {
            emoticonPanelViewAdapterInstance = it.thisObject;
        }

        // handleIPSite 只接受数字 epId，防崩
        HookUtils.hookBeforeIfEnabled(this,
        EmotionPanelViewPagerAdapter.method("handleIPSite")!!) {
            val pack = it.args[0];
            if(pack != null && parseQAEpId(pack.get<String>("epId")!!) != null) {
                it.args[0] = null;
            }
        }

        // 显示图标
        HookUtils.hookBeforeIfEnabled(this, EmoticonTabAdapter.method("generateTabUrl")!!) {
            val id = parseQAEpId(it.args[0] as String);

            if(id != null) {
                val provider = providers.find { it.uniqueId() == id.providerId };
                if(provider != null) {
                    val panel = provider.extraEmoticonList().find { it.uniqueId() == id.panelId };
                    if(panel != null) {
                        val url = panel.emoticonPanelIconURL();
                        it.result = java.net.URL(url);
                    }
                }
            }
        }

        var lastPanelDataSize = -1;
        var template: Any? = null;
        // 生成 Tab 面板
        HookUtils.hookAfterIfEnabled(this, EmoticonPanelController.method("getPanelDataList")!!) {
            // 移除自带面板
            // TODO: 做成可选
            // 鸽子：当然不是我来做（

            val list = it.result as MutableList<Any>;
            val iterator = list.iterator();

            if(template == null) {
                if(list.size < 8) return@hookAfterIfEnabled;
                template = list[7];
            }


            while(iterator.hasNext()) {
                val element = iterator.next();

                val typeWhiteList = listOf(
//                    13, // 表情商城,
//                    18, // 搜索表情,
                    7, // Emoji 表情,
                    4, // 收藏表情,
//                    6
//                    12, // GIF
//                    17, // QQ什么玩意专属表情
                );

                if(!typeWhiteList.contains(element.get<Int>("type")!!)) {
                    iterator.remove();
                }
            }

            var i = 3;
            // 添加自定义面板
            for(provider in providers) {
                for(panel in provider.extraEmoticonList()) {
                    i++;
                    val pack = EmoticonPackage.newInstance();
                    pack.set("epId", "qa:${provider.uniqueId()}:${panel.uniqueId()}");
                    pack.set("name", "QAExtraSticker");
                    pack.set("type", 3);
                    pack.set("ipJumpUrl", "https://github.com/cinit/QAuxiliary/");
                    pack.set("ipDetail", "QA");
                    pack.set("valid", true);
                    pack.set("status", 2);
                    pack.set("latestVersion", 1488377358);
                    pack.set("aio", true);

                    val info = EmoticonPanelInfo.newInstance(
                        Args(
                            arrayOf(
                                6, // type,
                                6, // columnNum,
                                pack
                            )
                        ),
                        ArgTypes(
                            arrayOf(
                                Int::class.javaPrimitiveType!!,
                                Int::class.javaPrimitiveType!!,
                                EmoticonPackage
                            )
                        )
                    );

                    list.add(info!!);
                }
            }

            if(lastPanelDataSize != list.size) {
                lastPanelDataSize = list.size;
                emoticonPanelViewAdapterInstance?.invoke("notifyDataSetChanged");
            }

            it.result = list;
        }
        Log.i("Fuck yes")
        // 面板数据
        HookUtils.hookBeforeIfEnabled(this, EmotionPanelViewPagerAdapter.method("getEmotionPanelData")!!) {
            Log.i("getEmotionPanelData: ${it.args[2].get("emotionPkg")}");
            val pkg = it.args[2].get("emotionPkg") ?: return@hookBeforeIfEnabled;
            val epid = pkg.get("epId")?: return@hookBeforeIfEnabled;
            val id = parseQAEpId(epid as String);
            Log.i("getEmotionPanelData: ${id}");
            if(id != null) {
                val provider = providers.find { it.uniqueId() == id.providerId };
                if(provider != null) {
                    val panel = provider.extraEmoticonList().find { it.uniqueId() == id.panelId };
                    if(panel != null) {
                        val emoticons = panel.emoticons();
                        val list = mutableListOf<Any>();
                        for(emoticon in emoticons) {
                            list.add(emoticon.QQEmoticonObject());
                        }
                        it.result = list;
                    }
                }
            }
        }

        return false;
    }

    override val isAvailable = QAppUtils.isQQnt();
}