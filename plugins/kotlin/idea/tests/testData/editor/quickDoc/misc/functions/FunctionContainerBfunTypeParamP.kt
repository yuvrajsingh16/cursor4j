abstract class FunctionContainerB {
    abstract fun funBodyReturnE()
    abstract fun <X, Y> funTypeParamB(p: X): Y
    abstract fun <Y: AuxFaceA?, X: Y & Any> funTypeParamH(p: X): Y
    abstract fun <F: suspend (AuxFaceA) -> AuxFaceB> funTypeParamK(fn: F)
    abstract fun <X, Y> <caret>funTypeParamP(x: X, y: Y) where X: AuxFaceA, Y: AuxFaceB

    abstract fun <X> AuxFaceD<X>.funReceiverC(): X

    abstract fun funValParamC(p: Int, vararg afa: AuxFaceA)
}
//INFO: <div class='definition'><pre><span style="color:#000080;font-weight:bold;">public</span> <span style="color:#000080;font-weight:bold;">abstract</span> <span style="color:#000080;font-weight:bold;">fun</span> <span style="">&lt;</span><span style="color:#20999d;">X</span><span style=""> : </span><span style="color:#000000;"><a href="psi_element://AuxFaceA">AuxFaceA</a></span>, <span style="color:#20999d;">Y</span><span style=""> : </span><span style="color:#000000;"><a href="psi_element://AuxFaceB">AuxFaceB</a></span><span style="">&gt;</span> <span style="color:#000000;">funTypeParamP</span>(
//INFO:     <span style="color:#000000;">x</span><span style="">: </span><span style="color:#20999d;"><a href="psi_element://FunctionContainerB.funTypeParamP.X">X</a></span>,
//INFO:     <span style="color:#000000;">y</span><span style="">: </span><span style="color:#20999d;"><a href="psi_element://FunctionContainerB.funTypeParamP.Y">Y</a></span>
//INFO: )<span style="">: </span><span style="color:#000000;"><a href="psi_element://kotlin.Unit">Unit</a></span></pre></div><div class='bottom'><icon src="KotlinBaseResourcesIcons.ClassKotlin"/>&nbsp;<a href="psi_element://FunctionContainerB"><code><span style="color:#000000;">FunctionContainerB</span></code></a><br/></div>
