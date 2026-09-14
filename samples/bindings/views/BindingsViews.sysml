package BindingsViews {
    private import BindingsModel::*;
    private import StandardViewDefinitions::*;

    // 互联视图：连接器一律画成边。`bind` 也应当出现在这里。
    view 'power binding' : InterconnectionView {
        expose controller;
    }

    // 同一组元素用 General 视图作对照：连接器在这里是节点/仓格，不是边。
    view 'binding structure' : GeneralView {
        expose controller;
    }
}
