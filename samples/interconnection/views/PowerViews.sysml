package PowerViews {
    private import PowerModel::*;
    private import StandardViewDefinitions::*;

    // 标准 Interconnection 视图：连接器应当成为边，端口贴在节点边界上
    view 'power interconnection' : InterconnectionView {
        filter @SysML::PartUsage or @SysML::PortUsage;
        expose powerSystem::**;
    }

    // 同一组暴露元素，用 General 视图作对照
    view 'power structure' : GeneralView {
        filter @SysML::PartUsage or @SysML::PortUsage;
        expose powerSystem::**;
    }
}
