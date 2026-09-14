package BindingsModel {
    port def PowerPort;

    part def Controller {
        port supply : PowerPort;
        port backup : PowerPort;
    }

    // `bind` 生成的 BindingConnector 是这个 part usage 的自有特征，
    // 而 `expose controller;` 不是递归 expose —— 正是"连接器没有被直接暴露"的情形。
    part controller : Controller {
        bind supply = backup;
    }
}
