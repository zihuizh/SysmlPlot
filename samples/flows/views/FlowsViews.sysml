package FlowsViews {
    private import FlowsModel::*;
    private import StandardViewDefinitions::*;

    view 'power flows' : InterconnectionView {
        filter @SysML::PartUsage or @SysML::PortUsage or @SysML::ItemUsage;
        expose powerSystem;
        expose Tank;
        expose Engine;
        expose FuelOutPort;
        expose FuelInPort;
    }
}
