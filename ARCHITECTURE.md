# 代码区域与依赖边界

项目按“战斗权威状态 → 战斗编排 → 界面”单向组织。UI 绝不能向 `core` 引入 JavaFX，
也不能自行调用 `World.step(...)`。

```
core/                                      战斗规则与权威状态（无 UI 依赖）
└─ com.arcanebrigade.core/
   ├─ World.java                           实体、碰撞、敌人、技能、掉落、Boss、升级
   ├─ Balance.java / WaveDirector.java     数值与刷怪节奏
   ├─ Spells.java / Passives.java          技能与被动定义
   ├─ Loadout.java / Stats.java             玩家构筑与派生属性
   └─ InputCommand.java                    UI 传入战斗层的纯数据命令

client/                                    JavaFX 界面与本地运行时
├─ com.arcanebrigade.client.battle/
│  └─ BattleSession.java                   对局创建、固定步长、暂停、升级选择的唯一入口
└─ com.arcanebrigade.client/
   ├─ GameApp.java                         菜单/大厅/鼠标键盘事件，将输入交给 BattleSession
   ├─ Renderer.java                         Canvas 战斗画面、HUD、菜单和升级面板
   ├─ GameConfig.java / GameAudio.java     本地设置与音频
   └─ Sprites.java / GifDecoder.java        视觉资源加载
```

## 职责约定

- 新增敌人、技能、伤害、元素、波次、Boss、掉落或升级规则：放在 `core`。
- 新增一局战斗的暂停、重开、固定帧推进、升级确认等流程：放在 `BattleSession`。
- 新增按钮、页面、输入事件、镜头、Canvas 绘制、音频和本地设置：放在 `client` UI 区域。
- UI 通过 `InputCommand` 提交移动、瞄准、按键；通过 `BattleSession` 请求对局操作；
  `Renderer` 只读取 `World` 状态绘制，不修改战斗状态。

这样以后接入联机时，主机端可以直接复用 `core`；替换 JavaFX UI 时也不需要改动战斗规则。
