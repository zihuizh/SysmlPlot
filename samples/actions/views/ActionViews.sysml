package ActionViews {
    private import ActionModel::*;
    private import StandardViewDefinitions::*;

    // 动作流视图：动作是节点，succession 与 flow 是边
    view 'action flow' : ActionFlowView {
        expose capture;
    }

    // 同一组元素换成 General 视图作对照：这里连接器也是节点
    view 'action structure' : GeneralView {
        expose capture;
    }
}
