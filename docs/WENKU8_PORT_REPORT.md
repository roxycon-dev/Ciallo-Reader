# Wenku8 UI / 浜や簰 / 鍔ㄦ晥绉绘浜や粯鎶ュ憡

> **Revision 3锛堝綋鍓嶏級**锛氭寜鈥滃鐢ㄥ弬鑰冧粨搴撳墠绔€佸祵鍏ユ湰杞欢銆佸悗绔笌浣嶇疆涓嶆敼鈥濈殑瑕佹眰缁х画銆?>
> - 鐩存帴璇诲彇 `motion_ui.dart`銆乣archive_motion.dart`銆乣app_interactions.dart`銆?>   `archive_icons.dart`銆乣archive_icon_motion.dart`銆乣glass_dock.dart` 鎻愬彇甯搁噺锛?>   contact 120 / enter 420 / exit 320 / coverFlight 1000 / coverReturn 840锛?>   `easeOutCubic`銆乣easeInCubic`銆乣settle(.2,1.08,.34,1)`銆乣plane(.18,.72,.18,1)`锛?>   鍥炬爣 380ms 鐘舵€?morph銆?40/240ms 鎸夊帇銆?40ms 涓夋媿鎵嬪娍锛汥ock spring mass .64/stiffness 270/damping 19銆?> - **鍒犻櫎鎶界焊椤甸潰杞満**锛歚paperComposable` 涓嶅啀浣跨敤鍏ㄥ睆 AGSL 绾搁潰 warp锛屾敼涓哄弬鑰?>   `CupertinoPageRoute` 璇箟鐨?420ms 妯悜鎺ㄥ叆 + 鍓嶉〉 1/3 瑙嗗樊锛岃繑鍥?320ms銆?>   `pullOutClickable`/`pullOutAnchor` 涓嶅啀娉ㄥ唽绾告簮鍑犱綍锛沗PullOutVisibility` 鏀逛负
>   杞婚噺 fade+scale锛涘簳閮?sheet 鏀圭敤 Material `ModalBottomSheet` 寮规€с€?> - **3D 涔︽灦缁樺埗鍘诲垎閰?*锛歅ath 澶嶇敤 rewind銆丳aint/Typeface/Matrix 缂撳瓨銆?>   涔﹁剨鏂囧瓧鎸夊瓧瀹界紦瀛樺苟鎵归噺 drawText銆佽涔夎妭鐐逛粠 60 涓檷鍒板綋鍓?宸﹀彸 3 涓€?>   灏侀潰璇锋眰 512鈫?56 涓斿叧闂?crossfade銆?> - **Dock**锛氭ā绯婂崐寰勫浐瀹氾紝涓嶅啀闅忔寜鍘嬮€愬抚閲嶅缓 RenderEffect锛涘浘鏍囧鍔?640ms
>   涓夋媿涓婃诞/缂╂斁/缃楃洏鏃嬭浆锛涙枃瀛楁寜閽笌鍥炬爣鎸夐挳澧炲姞鐐瑰嚮鑴夊啿銆?> - **鐜荤拑**锛氭殏鍋?`backdrop` 瀹炴椂鎹曡幏锛堝綋鍓嶅疄鐜版瘡涓?surface 姣忓抚閲嶅綍鏁撮〉锛夛紝
>   淇濈暀闈欐€佸崐閫忔槑鐜荤拑涓庢弿杈癸紱鍚庣画鍐嶄互鍙傝€冧粨搴撶殑婊ら暅缂撳瓨鏂瑰紡鎭㈠鍘熺敓鐜荤拑銆?> - **娴嬭瘯鏁版嵁**锛氬凡鐢?`Ciallo-backup.ciallo` 閫氳繃 `BackupManager.importArchive`
>   鏁存満杩佺Щ瀵煎叆锛? 鏈功銆?60 绔犮€? 鏉℃敹钘忋€?97 鏉￠槄璇昏褰曘€?30 鏉′細璇濄€?>
> 褰撳墠楠屾敹鍏ュ彛锛氫功鏋堕粯璁?3D锛涚偣涓ぎ/宸﹀彸姝ヨ繘/鍒囨崲鍒楄〃鍧囧彲鐢紱椤甸潰 push/pop
> 涓烘í鍚?Cupertino 杩囨浮锛屾棤绾搁潰 warp銆傜湡鏈烘€ц兘闇€浠ョ‖浠?GPU 涓哄噯锛涙湰鏈烘ā鎷熷櫒涓?> SwiftShader 杞欢娓叉煋锛?080脳2280 涓嬫墍鏈夊鏉傜晫闈㈢殑 GPU 甯ц€楁椂閮戒細琚斁澶с€?
> **Revision 2**锛氱敤鎴锋寚鍑洪鐗堜粛鏈夆€滃彧鎸夊垎鏋愭枃妗ｆ惉鍓嶇鈥濈殑闂銆?> 鏈疆宸叉敼涓虹洿鎺ラ槄璇?`Wenku8_Flutter_analysis/lib` 婧愮爜閲嶅仛锛?>
> 1. **椤堕儴鏍忔仮澶嶅師鐘?*锛歚TabScreenHeader.kt` 宸插洖婊氬埌浠撳簱 HEAD锛屼笉鍐嶄慨鏀归《閮ㄦ爮銆?> 2. **3D 涔︽灦鎸夋簮鐮侀噸寤?*锛氱洿鎺ュ鐓?`archive_wave.dart` 鐨?`_WavePose`銆?>    `ArchiveShelfSceneRender`銆乣ArchiveBoxDepthRender` 鍜?>    `archive_shelf_geometry.dart`锛屼慨姝ｄ簡棣栫増閿欒鐨勬í鍚戝眳涓笌 `baseY`銆?>    鐜板湪涔︽灦鐨勮捣鐐规槸 `width/2 - footprint/2 + pull*.66`锛宍baseY` 浣跨敤
>    `bodyHeight` 鑰屼笉鏄瑙堣鍙ｉ珮搴︼紱灏侀潰/椤堕潰/涔﹁剨鍏辩敤鍚屼竴椤剁偣涓庣煩闃点€?> 3. **涓嶅啀鏂板绗簩涓功鏋舵澘鍧?*锛氬垹闄や簡棣栫増鐨?`01 / ARCHIVE` 閲嶅鍖哄煙銆?>    鈥滄垜鐨勪功鏋垛€濅笅闈㈢洿鎺ユ槸 3D 涔︽灦锛涢粯璁?3D锛屽垎绫绘爮鏈熬鎻愪緵鈥滃垏鎹㈠垪琛ㄢ€濓紝
>    鍒楄〃妯″紡淇濈暀鍘熸潵鐨勭綉鏍笺€佸閫夈€佹嫋鎷借兘鍔涖€?> 4. **鎸夐挳鎸夋簮鐮佹帶浠堕噸鍋?*锛歚WenkuTextButton` 瀵圭収 `app_ui.dart` 鐨?>    `GlassTextButton`锛?2 鍦嗚銆佹渶灏?64脳48銆?4sp/500銆佸墠瀵?16dp 鍥炬爣銆?>    鏃?Material 涓嬪垝绾裤€佹棤绗簩鍦堝僵鑹叉弿杈癸紱鐢ㄤ簬鈥滈粯璁?/ 瀵煎叆鏂颁功 / 鏂板缓鍒嗙被鈥濄€?>    鏍囬涓庢寜閽媶鎴愪袱琛岋紝閬垮厤澶у瓧鍙锋妸鎸夐挳鎸ゅ嚭灞忓箷銆?> 5. **淇涔︽灦鎵嬪娍**锛歚ShelfSelectionHost` 澧炲姞 `gesturesEnabled`锛?>    3D 涔︽灦鎺ョ鏃跺叧闂埗绾у垪琛ㄦ墜鍔匡紱鍦ㄤ功鏋朵笂鏂瑰鍔犵湡瀹炲懡涓潰銆?> 6. **鎼滅储妗嗗洖閫€**锛氭寜鈥滈《閮ㄦ爮涓嶇敤鏀光€濈殑瑕佹眰锛屾仮澶嶅師 `OutlinedTextField`銆?>    鍙傝€冧粨搴撶殑 `AppSearchField` 瀹炵幇淇濈暀鍦?`WenkuGlass.kt` 浣滀负鍚庣画鍙帴鍏ョ粍浠躲€?>
> 褰撳墠楠屾敹鍏ュ彛锛氱湡鏈?妯℃嫙鍣ㄦ墦寮€鈥滀功鏋垛€濓紝榛樿鐪嬪埌 3D 涔︽灦锛?> 鐐逛腑澶功鏈墦寮€锛屽簳閮?`01 / 03` 鍒囨崲锛涒€滃垏鎹㈠垪琛ㄢ€濊繘鍏ュ師缃戞牸銆?> 鏈疆瀹炴満宸查獙璇侊細3D 涔︽灦鏄剧ず/灞呬腑銆佺偣涓ぎ鎵撳紑銆佸乏鍙虫杩涖€佲€滃垏鎹㈠垪琛ㄢ€濄€?> 椤堕儴鏍忎繚鎸佸師鏍枫€佷笁鏋氭搷浣滄寜閽柊鏉愯川銆傛按骞虫嫋鍔ㄤ唬鐮佷笌鎸夊帇鏃ュ織宸查€氾紝
> 浣嗘埅甯ф椂閫変腑鏍囬鍒锋柊鏈ǔ瀹氬鐜帮紝鍒楀叆涓嬩竴鏉″緟澶嶆牳椤广€?>
> 鏈 APK SHA-256锛歚AF4F3EC9ACBB6F7AD3CFA1412A2A95F3855AD70DCDDDC08537ECEC9F3B0F0133`
>
> 鏈満妯℃嫙鍣ㄤ负 `Android Emulator OpenGL ES Translator (Google SwiftShader)`锛岀函杞欢
> 鍏夋爡锛?080脳2280 涓嬪嵆浣跨畝鍗曠珷鑺傚崱鐗囦篃浼氬嚭鐜?1鈥?s GPU 甯с€傚悗缁€ц兘鍒ゆ柇蹇呴』鍖哄垎
> 鈥淪wiftShader 鐜涓婇檺鈥濆拰鈥滅湡瀹?GPU 涓婄殑浠ｇ爜寮€閿€鈥濓紝涓嶈兘鎶婃ā鎷熷櫒缁濆甯ц€楀綋鎴愮湡鏈虹粨璁恒€?
> 鍒嗘敮锛歚feature/wenku8-ui-port`锛堝綋鍓嶅伐浣滄爲浠?detached HEAD 鍒涘缓锛屾湭鏀瑰姩 `main`锛?> 鍙傝€冿細`docs/Wenku8_UI_鍓嶇瀹炵幇鍒嗘瀽.md`锛屼互鍙婃湰鍦版簮鐮佸揩鐓?> `Wenku8_Flutter_analysis/lib/*.dart`锛坈ommit ebcd7f1锛?> 鎶€鏈爤锛欿otlin / Jetpack Compose / Material 3锛堥潪 Flutter锛?> 楠岃瘉鍖咃細`com.aistudio.novelreader.kxmpzq.debug`锛宒ebug keystore 绛惧悕锛屼笌姝ｅ紡鐗堜笉鍚?
## 1. 浜や粯缁撴灉姒傝

涓荤晫闈㈠凡鎸夋枃妗ｇ殑璁捐璇█鍋氫簡鏁翠綋鍗囩骇锛屽悓鏃朵繚鐣欏師鏈変俊鎭灦鏋勶細

- 浜斾釜 Tab锛堜功搴撱€佸彂鐜般€佷功鏋躲€佺粺璁°€佽缃級鍏ㄩ儴淇濈暀銆?- 鏂板 Wenku8 浠ょ墝灞傦細绾?澧?鐜荤拑鑹叉澘銆? / 3 / 22 / 24 / 28 鍦嗚銆?20 / 320 / 420 / 620 / 1000 / 840 鏃堕暱銆佸洓鏉℃枃妗ｆ洸绾裤€乣mass .64 / stiffness 270 / damping 19` 寮圭哀銆乯elly銆? 甯﹀垪琛ㄥ欢杩熴€?- 鏂板缓涓€х焊闈㈢幓鐠冿紝鍏抽棴 specular / glow / dispersion锛涢珮瀵规瘮搴︽垨鍏抽棴妯＄硦鏃堕€€鍖栫函鑹层€?- 搴曢儴 Dock 閲嶅啓锛?20ms 鎸変笅銆?0% 璺熸墜銆侀€熷害褰㈠彉銆?00ms 鍥炲脊銆侀€熷害鎶曞皠銆佺幓鐠冨帤搴﹂殢鍘嬪姏澧炲姞銆?- 椤甸潰鍒囨崲鏀逛负鍙屽眰鏂滃垏骞抽潰杞満锛屼繚鐣欓槄璇诲櫒涓庢棦鏈夋娊绾搁〉闈㈣浆鍦恒€?- 鍙戠幇椤典笌涔︽灦椤垫帴鍏ュ垪琛ㄦ彮绀?/ 楠ㄦ灦鍒板唴瀹逛氦鎺ワ紙鍙戠幇椤碉級锛涜拷鍔犲垎椤典笉閲嶆挱鏃у唴瀹广€?- 涔︽灦鏂板 Canvas 鑷粯 3D 涔︽灦锛歝abinet projection銆乧os虏 娉㈠姩銆乸ull/lift銆乪^-4.4t 鎶曞皠銆乲144/d24 鍚搁檮銆佸彲寰幆涔︽灦銆侀€愪功璇箟鍛戒腑銆?- 灏侀潰椋炶鏃堕暱瀵归綈 1000ms锛涢槄璇诲櫒缈婚〉鎻愪氦璺濈/閫熷害闂ㄦ銆佺偣鍑诲乏鍙冲尯姣斾緥瀵归綈鏂囨。銆?- 绯荤粺楂樺姣斿害銆佸噺灏戝姩鏁堛€佸叧闂ā绯娿€佺敾璐ㄥ抚鐜囬檷绾у潎鍙敤銆?
## 2. 淇敼涓庢柊澧炴枃浠?
### 鏂板

| 鏂囦欢 | 浣滅敤 |
|-|-|
| `app/src/main/java/com/example/ui/wenku/WenkuTokens.kt` | 鑹叉澘銆佸渾瑙掋€侀棿璺濄€佸瓧浣撱€佹帶浠惰鏍笺€乄enkuMotion銆丏ock/涔︽灦/闃呰鍣ㄧ墿鐞嗗父閲忋€佹棤闅滅涓庨€€鍖?CompositionLocal |
| `app/src/main/java/com/example/ui/wenku/WenkuGlass.kt` | 涓€х幓鐠?`WenkuGlassSurface`銆佸浘鏍?鏂囧瓧/鑽父鎸夐挳銆丣elly 琛ㄩ潰銆丆ontentPanel銆丮enu銆佹鏍囬銆乭airline |
| `app/src/main/java/com/example/ui/wenku/WenkuMotion.kt` | 鍗曟帶鍒跺櫒鍒楄〃鎻ず銆佹枩鍒囧钩闈?Tab 杞満銆侀槄璇诲櫒鎺т欢灞傘€佹粴鍔ㄨ竟缂樻笎闅愩€侀鏋躲€佸姞杞戒氦鎺?|
| `app/src/main/java/com/example/ui/wenku/ArchiveShelf.kt` | 3D 涔︽灦 Canvas銆佹尝鍔ㄣ€佹姇灏勫惛闄勩€佸惊鐜Ы浣嶃€侀€愪功璇箟鍛戒腑 |
| `docs/wenku8-ui-port-plan.md` | 寮€宸ュ墠鐨勬敼閫犳柟妗堜笌鎶€鏈爤鏄犲皠 |
| `docs/WENKU8_PORT_REPORT.md` | 鏈姤鍛?|

### 淇敼

| 鏂囦欢 | 鏀瑰姩 |
|-|-|
| `app/src/main/java/com/example/ui/theme/Theme.kt` | 浜?鏆楃焊闈㈣壊銆乻urface 灞傜骇銆乷utline銆乻urfaceTint=绌猴紱鍝佺墝涓昏壊淇濈暀 |
| `app/src/main/java/com/example/ui/components/GlassCard.kt` | 涓荤晫闈㈢幓鐠冨垎鏀垏鍒?`WenkuGlassSurface`锛涢槄璇诲櫒浣滅敤鍩熶笉鍚敤 |
| `app/src/main/java/com/example/ui/components/AppBottomTabBar.kt` | 閲嶅啓涓?Wenku Glass Dock锛涗繚鐣?`AppTabItem`銆佹姌鍙犵姸鎬併€乼estTag銆佹棤闅滅 |
| `app/src/main/java/com/example/ui/components/TabScreenHeader.kt` | 56dp 宸ュ叿鏍?+ 鑻辨枃灏忔爣 + 灞呬腑琛嚎鏍囬 + 婊氬姩缇藉寲锛涙悳绱㈠睍寮€璧版暣琛?|
| `app/src/main/java/com/example/MainActivity.kt` | Tab 鍒囨崲鎺ュ叆 `WenkuPlaneTabSwitcher`锛涢槄璇诲櫒鍥涗釜璺敱鍏抽棴涓荤晫闈㈡潗璐紱鐢昏川甯х巼涓婇檺锛涗繚鐣欐棦鏈夊鑸?|
| `app/src/main/java/com/example/ui/HomeScreen.kt` | 3D 涔︽灦銆佸垪琛ㄦ彮绀恒€佹悳绱㈠浘鏍囦笌鐒︾偣銆丠ero CTA 鏀逛负 Wenku 鑽父銆佸皝闈㈤琛?1000ms |
| `app/src/main/java/com/example/discover/DiscoverScreen.kt` | 鍙戠幇鍒楄〃鎺ュ叆鍔犺浇浜ゆ帴 / 鎻ず锛岄灞忛鏋跺埌鍐呭浜ゆ帴 |
| `app/src/main/java/com/example/discover/DiscoverCards.kt` | 鍒犻櫎姣忓崱 30ms 娣″叆锛岀粺涓€璧?Wenku 鍗曟帶鍒跺櫒鎻ず |
| `app/src/main/java/com/example/ui/ComicChaptersScreen.kt` | 璇︽儏搴曢儴涓?CTA 鏀逛负涓€?Wenku 鑽父 |
| `app/src/main/java/com/example/ui/ReaderScreen.kt` | 灏侀潰鍏变韩椋炶鏃堕暱鏀逛负 1000ms锛涙鏂囦笌闃呰鎺т欢瑙嗚涓嶅姩 |
| `app/src/main/java/com/example/ui/pageturn/PageTurnContainer.kt` | 缈婚〉鎻愪氦璺濈 `clamp(viewport*.1, 28, 48)`銆侀€熷害 360銆佸洖鎷夊弽杞紱300/340/440/620ms 鍒嗘晥鏋滄椂闀匡紱宸﹀彸鐐瑰嚮鍖?28% / 72% |
| `app/src/main/java/com/example/ui/theme/ModifierExtensions.kt` | 鍏ㄥ眬鐐瑰嚮鍙嶉鏀逛负 .97 / 120ms / settle |
| `app/src/main/java/com/example/data/AppDatabase.kt` | 杩愯鏃朵慨澶嶏細`PRAGMA journal_size_limit` 鏀圭敤 query锛岄伩鍏?Android SQLite 鎷掔粷 execSQL |
| `app/src/main/java/com/example/data/BookRepository.kt` | 鍚屼笂锛宍PRAGMA wal_checkpoint` 鏀圭敤 query锛堜粛鍖呭湪 runCatching锛?|

## 3. 鏂囨。瀵圭収琛?
### 3.1 鍘熸牱绉绘锛堟暟鍊间竴鑷达級

| 鏂囨。绔犺妭 | 鍐呭 | 鐘舵€?|
|-|-|-|
| 搂2.1 | 浜?鏆楄儗鏅€侀潰鏉裤€佷钩鐧姐€佹姮璧峰眰銆佹弿杈广€佹澧ㄨ壊鍊?| 宸插師鏍疯繘鍏?`WenkuTokens` 涓?Theme |
| 搂2.1 / 搂4.5 | 闈㈡澘 4銆佸皝闈?3銆佸浘鏍?24銆佹枃瀛?22銆佺幓鐠?28 鍦嗚 | 宸蹭綔涓烘柊缁勪欢浠ょ墝 |
| 搂3.1 | 120 / 420 / 320 / 1000 / 840ms | 宸插師鏍?|
| 搂3.1 | easeOutCubic / easeInCubic / Cubic(.2,1.08,.34,1) | 宸插師鏍?|
| 搂3.1 | mass .64 / stiffness 270 / damping 19 | 浠ョ瓑闃诲凹姣?0.723 鏄犲皠 Compose SpringSpec |
| 搂3.1 | `jelly(t)=sin(3蟺t)(1-t)^2` | 宸插疄鐜?|
| 搂3.2 | 鍗曟帶鍒跺櫒 420ms銆乣clamp(order,0,8)*.055`銆佺獥鍙?.52 | 宸插疄鐜?|
| 搂3.4 | `Cubic(.18,.72,.18,1)`銆伮?4/12/10/-4 浣嶇Щ銆乣min(180,width*.46)` 鏂滃垏 | 宸插疄鐜?|
| 搂4.5 | 46脳46 鍥炬爣鎸夐挳銆?4 楂樿嵂涓搞€?4脳48 鏂囧瓧鎸夐挳銆佸崟閫夋贩鑹?10% | 宸插疄鐜?|
| 搂4.5 | 鑿滃崟瀹?248 / 鏈€澶?440銆丼heet 550 閫熷害 / 24% / 420ms 闃堝€?| 浠ょ墝宸茶惤锛涘涓绘部鐢ㄩ」鐩?PullOut 浣撶郴 |
| 搂5.1 | 浜?Tab 浣嶇疆淇濈暀銆佺嫭绔嬮〉闈㈢姸鎬?| 淇濈暀 |
| 搂5.3 | 璺熸墜 .8銆佸舰鍙?.028 / clamp 卤24 / lerp .55 / 100ms銆佹姇灏?.055 / 卤.22銆佸帇鍔?.022/.065銆佸帤搴?+2 | 宸插疄鐜?|
| 搂8.1 | maxViewport 520銆?66/-.30銆乻pine/cover/body/pull/rail 鍏紡 | 宸插疄鐜帮紱baseY 鍥犻瑙堥珮搴︽敼涓哄簳杈归敋瀹氾紙瑙?3.2锛?|
| 搂8.2 | cos虏/1.48 pitch銆乸ull銆乴ift銆乷rigin | 宸插疄鐜?|
| 搂8.4 | e^-4.4t銆乿0/4.4 鎶曞皠銆乲144/d24 鍚搁檮 | 宸插疄鐜?|
| 搂10.5 | 340/300/300/440/620ms | 宸叉槧灏勫埌 PageTurnContainer |
| 搂10.6 | 鎻愪氦璺濈銆?60 閫熷害銆佸洖鎷夊弽杞?| 宸插疄鐜?|
| 搂10.8 | 鏍囧噯 28% / 72% 鐐瑰嚮鍖?| 宸插疄鐜帮紙淇濈暀椤圭洰鑷畾涔夌儹鍖鸿鐩栬兘鍔涳級 |
| 搂14 | 鍏抽敭甯搁噺閫熸煡 | 闆嗕腑鍦?`WenkuTokens` |
| 闄勫綍 A | 缁熶竴鏇茬嚎銆佷笉鐢?setState 椹卞姩閫愬抚銆佷笉鍋氱湡 3D 寮曟搸銆佷繚鐣欓€€鍖?| 宸叉墽琛?|

### 3.2 杩戜技瀹炵幇锛堝樊寮傚凡鏍囨槑锛?
| 鏂囨。鑳藉姏 | 鏈瀹炵幇 | 宸紓 / 鍘熷洜 |
|-|-|-|
| Liquid Glass 鍏夊 shader | 鐜版湁 Backdrop 楂樻柉妯＄硦 + 涓€?tint锛涘叧闂?specular/glow/dispersion | Compose/Backdrop 娌℃湁 Impeller 瑙ｆ瀽鐜荤拑缁勫悎婊ら暅锛涗繚鐣欓珮瀵规瘮搴?鍏虫ā绯婇€€鍖?|
| 涓荤晫闈?Tab 鏂滃垏杞満 | 鍙屽眰 Compose銆丳ath 鏂滃垏瑁佸壀銆乨raw 闃舵璇昏繘搴?| 涓嶆槸 Flutter RenderObject锛涘嚑浣曚笌鏁板€兼寜鏂囨。 |
| 3D 涔︽灦 | Canvas cabinet projection + Coil painter 浠垮皠灏侀潰 | 鏂囨。缂?`archive_shelf_geometry.dart`锛涙寜绗?8 鑺傜嫭绔嬮噸鍐欙紝灞炩€滆嚜鐮旇繎浼尖€?|
| 3D 涔︽灦 baseY | 鏀逛负鈥滃钩鏀句功搴曡创棰勮搴曡竟鈥濓紝淇濈暀 pull/lift/depth | 鏂囨。鍘?baseY 闈㈠悜鏇撮珮鑸炲彴锛涘湪 360dp 棰勮鏉″唴浼氳鍒囷紝鍙敼閿氱偣 |
| 3D 涔︽灦寰幆 | 浣跨敤鏂囨。 repeatFill 鎬濊矾锛岄檺鍒舵瘡渚?7 涓Ы浣?| 闃叉灏忎功搴撻噸澶嶅皝闈㈣繃澶氾紱淇濈暀涓棿娉㈠嘲 |
| 灏侀潰 Hero 椋炶 | SharedTransition + 1000ms / ease锛沗PaperCoverRectTween` 鏃犲叕寮€绛変环鐗?| 娌℃湁 Flutter 鐨?18 娆′簩鍒嗗弽瑙?smoothstep锛屼笉鍋氫吉閫?|
| 闃呰鍣ㄦ帶浠跺懠鍑?| 娌跨敤鏃㈡湁 PullOut 绾哥墖瀹夸富 | 鏈敼涓烘枃妗ｇ殑涓ゅ眰鐙珛 Offset锛岄伩鍏嶇牬鍧忕幇鏈夐槄璇诲櫒绐楀彛/绾稿紶鐢熷懡鍛ㄦ湡 |
| 鎼滅储灞曞紑 | 澧炲姞鎼滅储鍥炬爣銆佽嚜鍔ㄨ仛鐒︺€佹暣琛岃緭鍏?| 鏈疄鐜?`46 + (width-46)*ease + jelly` 瀹藉害褰㈠彉 |
| 鍥炬爣绯荤粺 | 缁х画浣跨敤 Material Icons锛屾柊澧炰腑鎬у昂瀵?鍛戒腑瑙勮寖 | 鏂囨。 89 涓?Path 鍥炬爣鏈鍒讹紱椤圭洰璁稿彲璇佷笌宸ヤ綔閲忓師鍥?|
| 鍒楄〃鎻ず | 涔︽灦銆佸彂鐜般€佺粺璁＄瓑涓诲尯鍩?| 娌℃湁鎶婃瘡涓簩绾ч〉閮芥敼鎴愮嫭绔嬪簭鍒楋紱閬垮厤澶ц寖鍥村洖褰?|
| 楂樺姣斿害 | 璇?`high_text_contrast_enabled` / accessibility 瀹夊叏璁剧疆 | 娌℃湁鍦ㄨ缃〉澧炲姞鏄惧紡寮€鍏筹紝绯荤粺寮€鍏冲嵆鏃剁敓鏁堬紙閲嶅惎鍚庨噸璇伙級 |

### 3.3 鏈疄鐜?
| 鏂囨。鑳藉姏 | 鍘熷洜 |
|-|-|
| `reader_paper_geometry.dart` 鐨勭焊椤靛嵎鏇茬綉鏍笺€佹硶绾裤€乁V銆佽儗闈?| 鏂囨。鏄庣‘缂哄け婧愮爜锛屾嫆缁濈紪閫犲師鍏紡锛涚幇鏈?Harism 鍗烽〉淇濈暀 |
| 娈佃惤鎺ョ画 620ms 鍏ㄩ噺閲嶅啓銆乧over/fade 鐨?Flutter 鑷畾涔夌敾甯?| 灞炰簬闃呰鍣ㄨ瑙?娓叉煋鍐呴儴锛涙湰娆″彧鏀规椂闀裤€佺儹鍖恒€佹彁浜ょ墿鐞嗭紝淇濇寔姝ｆ枃鎺掔増涓嶅彉 |
| 60 / 90 / 120 甯х巼涓夌骇璁剧疆椤?| 娌℃湁鏂板璁剧疆椤癸紱鎸夌敾璐ㄦ。浣嶆槧灏勶細鏋佺畝/娴佺晠 60銆佸潎琛?90銆侀珮/鏋佽嚧璁惧榛樿 |
| 瀹屾暣 Sheet 鎷栨嫿瀹夸富銆佽彍鍗曟诞灞傞伄缃?16% | 娌跨敤椤圭洰鏃㈡湁 PullOut / Acrylic 瀹夸富锛涙柊澧?`WenkuMenu` 鍙彁渚涘昂瀵镐笌鏉愯川 |
| 89 涓?`ArchiveSymbol` 鑷畾涔夊浘鏍?| 鏈鍒?GPL 浠ｇ爜锛涚户缁敤 Material 鍥炬爣 |

## 4. 淇濈暀涓庢敼鍔?
### 淇濈暀
- 浜?Tab 淇℃伅鏋舵瀯銆佹墍鏈夊叆鍙ｄ笌涓氬姟鑳藉姏銆?- 闃呰鍣ㄦ鏂囥€佸垎椤点€侀€夋嫨/鎵规敞/璇嶅吀/鏌ユ壘銆乀TS銆佹极鐢婚槄璇?缈昏瘧銆?- 鐢ㄦ埛涓婚鑹层€佸搧鐗?LOGO銆佸紑灞忋€佽嚜瀹氫箟鑳屾櫙銆佷功婧?涓嬭浇/闅愮閫昏緫銆?- 鍘熸湁鎶界焊寮瑰眰鍜?PullOut 鐢熷懡鍛ㄦ湡銆?
### 鏀瑰姩
- 涓荤晫闈㈣儗鏅粠绾櫧/绾粦鍙樹负绾歌壊 `#F4F3EF / #191919`銆?- 鐜荤拑浠庡僵鑹叉恫鎬侀珮鍏夋敼涓轰腑鎬х焊闈㈢幓鐠冿紱鍝佺墝鑹插彧鐣欏湪閫変腑銆佽繘搴︺€佸浘琛ㄣ€佸紑鍏炽€?- 搴曢儴鏍忎粠鍝佺墝缁胯兌鍥婃敼涓虹焊闈㈢幓鐠?Dock锛涢€変腑椤逛负绾稿ⅷ鑹层€?- 椤甸潰鏍囬鏀逛负灞呬腑琛嚎 + 鑻辨枃灏忔爣 + 缇藉寲澶淬€?- 涔︽灦 Hero CTA 鏀逛负榛?鐧借嵂涓革紙鏂囨。鐨勪富鎸夐挳璇█锛夈€?- 鏂板 3D 涔︽灦棰勮锛屼粎鍦ㄥ綋鍓嶅垎绫?鈮? 鏈椂鍑虹幇銆?- 鍙戠幇鍗＄墖鍏ュ満浠庢瘡鍗＄嫭绔?30ms 寤惰繜鏀逛负缁熶竴 420ms 鍗曟帶鍒跺櫒銆?- 璇︽儏椤典富 CTA 鏀逛负涓€ц嵂涓革紝浠嶄繚鐣欏師鐐瑰嚮閫昏緫銆?
## 5. 楠岃瘉鏂规硶

### 鏋勫缓

```powershell
$env:JAVA_HOME='D:\android studio\jbr'
.\gradlew.bat :app:assembleDebug -PincludeX86 --no-build-cache
```

> 鏈満 Gradle build cache 鏇炬妸 KSP 鐢熸垚鐨?`AppDatabase_Impl.java` 鎺掗櫎鍦?`compileDebugJavaWithJavac` 缂撳瓨澶栵紝瀵艰嚧棣栨瀹夎鍑虹幇 `AppDatabase_Impl does not exist`銆傞噸鏂版墽琛?`:app:compileDebugJavaWithJavac --rerun-tasks --no-build-cache` 鍗冲彲鎭㈠锛涙渶缁堝寘宸插寘鍚绫汇€?> 绾湡鏈哄寘鍙渷鐣?`-PincludeX86`锛屼綋绉洿灏忋€?
APK锛歚app/build/outputs/apk/debug/app-debug.apk`
鍖呭悕锛歚com.aistudio.novelreader.kxmpzq.debug`
绛惧悕锛歚CN=Android Debug`锛堜笌姝ｅ紡鍖呬笉鍚岋紝瀹夎鍓嶉渶鍗歌浇鏃?debug 鍖呮垨 `adb install -r -d`锛?
### 瀹炴満鍥炲綊锛堝凡鍋氾級

- 鍐峰惎鍔?鈫?Onboarding 鈫?涓荤晫闈€?- 浜斾釜 Tab 閫愪竴鍒囨崲銆佽缃〉婊氬姩銆佸彂鐜伴〉鍔犺浇銆?- 鏈湴 EPUB 瀵煎叆锛歚sample.epub`銆乣sample3.epub` 鎴愬姛锛涗功鏋舵樉绀?Hero / 涔︽灦缃戞牸 / 3D 涔︽灦銆?- 鎵撳紑 EPUB 闃呰鍣ㄣ€佸浘鐗囧叏灞忋€佸叧闂€佷笅涓€绔犮€?- 楂樺姣斿害绯荤粺寮€鍏?鈫?Dock 閫€鍖栦负骞抽摵锛岀幓鐠冨彉瀹炶壊锛涘叧闂悗鎭㈠銆?- 灞忓箷鏃嬭浆銆佺郴缁熷瓧浣撴墿灞曟部鐢ㄧ幇鏈夎祫婧愪笌閽冲埗閫昏緫銆?
### 鑲夌溂楠岃瘉閲嶇偣

1. 涓荤晫闈細椤堕儴鏍囬灞呬腑銆佺焊鑹茶儗鏅€佸簳閮?Dock 閫変腑椤圭Щ鍔紱妯悜鎷栧姩 Dock锛岃兌鍥婁細璺熸墜/鎷変几銆?2. 涔︽灦 鈮? 鏈細`01 / ARCHIVE` 涓嬪嚭鐜?3D 涔︽灦锛涗腑蹇冧功鎶捣锛屽乏鍙?cos虏 琛板噺锛屾í鍚戠敥鍔ㄥ悗鍚搁檮銆?3. 鍒?Tab锛氭枩鍒囧钩闈粠涓€渚ф帹杩涳紱蹇€熻繛鐐逛笉浼氱暀涓嬫棫椤甸潰銆?4. 璇︽儏椤碉細涓?CTA 涓洪粦/鐧借嵂涓革紝鐐瑰嚮浠嶈兘闃呰銆?5. 闃呰鍣細涓ぎ鐐瑰嚮鍛煎嚭椤?搴曟帶鍒舵爮锛涘乏鍙?28%/72% 鐐瑰嚮锛涚炕椤佃揪鍒版彁浜よ窛绂绘垨 360 閫熷害闂ㄦ鎵嶆彁浜わ紝鍙嶅悜蹇€熷洖鎷夊洖鍘熶綅銆?6. 鍒楄〃锛氬彂鐜伴〉棣栧睆楠ㄦ灦 鈫?鍐呭鍗曟帶鍒跺櫒鎻ず锛涜拷鍔犲垎椤典笉閲嶆挱宸叉湁鍗＄墖銆?
### 閫€鍖栧紑鍏?
- **鍏抽棴妯＄硦**锛氳缃?鈫?娓叉煋鐢昏川 鈫?鍧囪　鍙婁互涓嬶紙涓嶅惎鐢?realtimeGlass锛夈€?- **鍑忓皯鍔ㄦ晥**锛氱郴缁熷紑鍙戣€呴€夐」鍔ㄧ敾缂╂斁鍏抽棴锛涙垨鐢昏川閫夈€屾瀬绠€銆嶃€?- **楂樺姣斿害**锛氱郴缁熸棤闅滅楂樺姣斿害鏂囧瓧锛汥ock/鐜荤拑/鎸夐挳鍒囧疄鑹层€?- **甯х巼**锛氭瀬绠€/娴佺晠 60銆佸潎琛?90銆侀珮/鏋佽嚧璁惧榛樿锛堢獥鍙?`preferredRefreshRate`锛孫EM 鍙拷鐣ワ級銆?
## 6. 宸茬煡闂涓庡悗缁缓璁?
1. **3D 涔︽灦鏄瑙堝瀷鑷粯**锛氬皝闈㈢汗鐞嗘寜 Canvas 浠垮皠缁樺埗锛屼笉鎻愪緵 Flutter 鍘熺増閫愰《鐐规硶绾垮厜鐓э紱褰撳墠杈圭紭涔﹂噸澶嶇敱寰幆妲戒綅浜х敓锛屼功搴撳皯浜?4 鏈椂浼氶噸澶嶅皝闈€傚缓璁悗缁寜鐪熷疄涔﹀簱瑙勬ā锛堚墺8 鏈級楠屾敹锛屾垨缁欏皯浜?4 鏈殑鍦烘櫙鏀规í鍚戣疆鎾€?2. **3D 涔︽灦鍗犵敤 360dp 楂樺害**锛氬皬灞忛灞忎細鎶娿€屾垜鐨勪功鏋躲€嶆爣棰樻帹鍒?Dock 涓嬫柟锛岄渶瑕佷笂婊戯紱鍚庣画鍙寜灞忛珮鍦?280鈥?60dp 闂磋嚜閫傚簲銆?3. **鎼滅储瀹藉害/jelly 鏈仛**锛氱洰鍓嶆槸鏁磋灞曞紑 + 鑷姩鑱氱劍锛涜嫢闇€瑕佸鍒诲弬鑰冪殑 `46 + (maxWidth-46)*ease` 涓庢í鍚?jelly锛岄渶瑕佹敼閫?`TabScreenHeader` 鐨勬悳绱㈡Ы浣嶃€?4. **鑿滃崟/Sheet 浠嶈蛋椤圭洰 PullOut**锛氭枃妗ｇ殑 550 閫熷害/24% 鍏抽棴闃堝€间粎钀藉湪浠ょ墝灞傦紝鐜版湁寮瑰眰鐨勭獥鍙ｇ敓鍛藉懆鏈熶笌绾哥墖鍑犱綍鏇村己锛岀洿鎺ユ浛鎹㈤闄╁ぇ銆?5. **闃呰鍣ㄧ炕椤佃瑙夋湭閲嶅啓**锛氱幇鏈夊嵎椤?瑕嗙洊/骞崇Щ/娓愬彉淇濈暀锛涘彧瀵归綈浜嗘彁浜ょ墿鐞嗐€佸乏鍙崇儹鍖哄拰鏃堕暱銆傜焊椤靛嵎鏇茬綉鏍煎洜鏂囨。缂哄け涓嶅疄鐜般€?6. **鍥炬爣**锛氫粛涓?Material 鍥炬爣锛涜嫢闇€瑕?89 涓。妗堝浘鏍囷紝闇€瑕佹寜鏂囨。 搂2.4 鐙珛缁樺埗璺緞骞跺仛 SVG/Compose 鍙岀瀵煎嚭銆?7. **涓荤晫闈㈡部鐢ㄩ」鐩棦鏈夌豢鑹叉恫鎬佸垎娈甸€夋嫨鍣ㄤ笌婊戞潯**锛氬畠浠睘浜庡搧鐗屼氦浜掞紝宸蹭繚鐣欙紱鑻ヨ拷姹傛枃妗ｇ殑涓€х焊闈㈢粷瀵圭粺涓€锛屽彲鍚庣画鏇挎崲涓?`WenkuTextButton`/`WenkuContentPanel` 鍙樹綋銆?8. **甯х巼**锛歚preferredRefreshRate` 鍙槸璇锋眰锛岄儴鍒?OEM 浼氬拷鐣ワ紱娌℃湁鏂板 60/90/120 涓夋。璁剧疆椤点€?9. **鏃?debug 鍖呭崌绾?*锛氬綋鍓?debug versionCode 涓?247锛岃嫢鏈満宸叉湁鏇撮珮 versionCode 鐨?debug 鍖咃紝浣跨敤 `adb install -r -d`锛屾垨鍏堝嵏杞芥棫 debug 鍖咃紙涓嶅奖鍝嶆寮忓寘鏁版嵁锛夈€?10. **璁稿彲璇?*锛氬弬鑰冮」鐩负 GPL-3.0锛涙湰娆′粎渚濇嵁鍒嗘瀽鏂囨。鐙珛閲嶅啓绠楁硶涓庝氦浜掞紝鏈鍒跺叾婧愮爜銆傛柊澧炴枃浠跺彲鎸夐」鐩師 MIT 璁稿彲鍙戝竷锛涜嫢鍚庣画鐩存帴绉绘婧愮爜锛岄渶瑕侀噸鏂拌瘎浼?GPL 鍒嗗彂涔夊姟銆?
## 7. 杩愯鏃朵慨姝ｈ鏄?
涓鸿娴嬭瘯鍖呰兘鐪熸鍚姩锛屼慨澶嶄簡涓や釜鏃㈡湁宕╂簝鐐癸紙涓嶆敼涓氬姟璇箟锛夛細

- `AppDatabase.kt`锛歚PRAGMA journal_size_limit=...` 浠?`execSQL` 鏀逛负 `query(...).use { moveToFirst() }`銆?- `BookRepository.kt`锛歚PRAGMA wal_checkpoint(TRUNCATE)` 鍚屾牱鏀逛负 `query`銆?
Android SQLite 瀵?PRAGMA 鍙帴鍙?query/rawQuery锛涘師璋冪敤浼氬湪鏁版嵁搴?onOpen 鎶?`Queries can be performed using SQLiteDatabase query or rawQuery methods only`銆傝闂涓?UI 绉绘鏃犲叧锛屼絾浼氶樆姝换浣曠晫闈㈠惎鍔ㄣ€?


















