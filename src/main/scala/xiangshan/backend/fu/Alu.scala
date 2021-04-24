package xiangshan.backend.fu

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import utils.{LookupTree, ParallelMux, SignExt, ZeroExt}
import xiangshan._

class AddModule(implicit p: Parameters) extends XSModule {
  val io = IO(new Bundle() {
    val src1, src2 = Input(UInt(XLEN.W))
    val out = Output(UInt((XLEN+1).W))
  })
  io.out := io.src1 +& io.src2
}

class SubModule(implicit p: Parameters) extends XSModule {
  val io = IO(new Bundle() {
    val src1, src2 = Input(UInt(XLEN.W))
    val out = Output(UInt((XLEN+1).W))
  })
  io.out := (io.src1 +& (~io.src2).asUInt()) + 1.U
}

class LeftShiftModule(implicit p: Parameters) extends XSModule {
  val io = IO(new Bundle() {
    val shamt = Input(UInt(6.W))
    val sllSrc = Input(UInt(XLEN.W))
    val sll = Output(UInt(XLEN.W))
  })
  io.sll := (io.sllSrc << io.shamt)(XLEN - 1, 0)
}

class RightShiftModule(implicit p: Parameters) extends XSModule {
  val io = IO(new Bundle() {
    val shamt = Input(UInt(6.W))
    val srlSrc, sraSrc = Input(UInt(XLEN.W))
    val srl_l, srl_w, sra_l, sra_w = Output(UInt(XLEN.W))
  })
  io.srl_l := io.srlSrc >> io.shamt
  io.srl_w := io.srlSrc(31, 0) >> io.shamt
  io.sra_l := (io.sraSrc.asSInt() >> io.shamt).asUInt()
  io.sra_w := (Cat(Fill(32, io.sraSrc(31)), io.sraSrc(31, 0)).asSInt() >> io.shamt).asUInt()
}

class MiscResultSelect(implicit p: Parameters) extends XSModule {
  val io = IO(new Bundle() {
    val func = Input(UInt())
    val sll, slt, sltu, xor, srl, or, and, sra = Input(UInt(XLEN.W))
    val miscRes = Output(UInt(XLEN.W))

  })
  io.miscRes := ParallelMux(List(
    ALUOpType.and  -> io.and,
    ALUOpType.or   -> io.or,
    ALUOpType.xor  -> io.xor,
    ALUOpType.slt  -> ZeroExt(io.slt, XLEN),
    ALUOpType.sltu -> ZeroExt(io.sltu, XLEN),
    ALUOpType.srl  -> io.srl,
    ALUOpType.sll  -> io.sll,
    ALUOpType.sra  -> io.sra
  ).map(x => (x._1 === io.func(3, 0), x._2)))
}

class AluResSel(implicit p: Parameters) extends XSModule {
  val io = IO(new Bundle() {
    val func = Input(UInt())
    val isSub = Input(Bool())
    val addRes, subRes, miscRes = Input(UInt(XLEN.W))
    val aluRes = Output(UInt(XLEN.W))
  })
  val isAddSub = ALUOpType.isAddSub(io.func)
  val res = Mux(ALUOpType.isAddSub(io.func),
    Mux(io.isSub, io.subRes, io.addRes),
    io.miscRes
  )
  val h32 = Mux(ALUOpType.isWordOp(io.func), Fill(32, res(31)), res(63, 32))
  io.aluRes := Cat(h32, res(31, 0))
}

class AluDataModule(implicit p: Parameters) extends XSModule {
  val io = IO(new Bundle() {
    val src1, src2 = Input(UInt(XLEN.W))
    val func = Input(FuOpType())
    val pred_taken, isBranch = Input(Bool())
    val result = Output(UInt(XLEN.W))
    val taken, mispredict = Output(Bool())
  })
  val (src1, src2, func) = (io.src1, io.src2, io.func)

  val isAdderSub = (func =/= ALUOpType.add) && (func =/= ALUOpType.addw)
  val addModule = Module(new AddModule)
  addModule.io.src1 := src1
  addModule.io.src2 := src2
  val subModule = Module(new SubModule)
  subModule.io.src1 := src1
  subModule.io.src2 := src2
  val addRes = addModule.io.out
  val subRes = subModule.io.out
  val xorRes = src1 ^ src2
  val sltu = !subRes(XLEN)
  val slt = xorRes(XLEN-1) ^ sltu

  val isW = ALUOpType.isWordOp(func)
  val shamt = Cat(!isW && src2(5), src2(4, 0))

  val leftShiftModule = Module(new LeftShiftModule)
  leftShiftModule.io.sllSrc := src1
  leftShiftModule.io.shamt := shamt

  val rightShiftModule = Module(new RightShiftModule)
  rightShiftModule.io.shamt := shamt
  rightShiftModule.io.srlSrc := src1
  rightShiftModule.io.sraSrc := src1

  val sll = leftShiftModule.io.sll
  val srl = Mux(isW, rightShiftModule.io.srl_w, rightShiftModule.io.srl_l)
  val sra = Mux(isW, rightShiftModule.io.sra_w, rightShiftModule.io.sra_l)

  val miscResSel = Module(new MiscResultSelect)
  miscResSel.io.func := func(3, 0)
  miscResSel.io.sll := sll
  miscResSel.io.slt := ZeroExt(slt, XLEN)
  miscResSel.io.sltu := ZeroExt(sltu, XLEN)
  miscResSel.io.xor := xorRes
  miscResSel.io.srl := srl
  miscResSel.io.or := (src1 | src2)
  miscResSel.io.and := (src1 & src2)
  miscResSel.io.sra := sra

  val miscRes = miscResSel.io.miscRes

  val aluResSel = Module(new AluResSel)
  aluResSel.io.func := func
  aluResSel.io.isSub := isAdderSub
  aluResSel.io.addRes := addRes
  aluResSel.io.subRes := subRes
  aluResSel.io.miscRes := miscRes
  val aluRes = aluResSel.io.aluRes

  val branchOpTable = List(
    ALUOpType.getBranchType(ALUOpType.beq)  -> !xorRes.orR,
    ALUOpType.getBranchType(ALUOpType.blt)  -> slt,
    ALUOpType.getBranchType(ALUOpType.bltu) -> sltu
  )
  val taken = LookupTree(ALUOpType.getBranchType(func), branchOpTable) ^ ALUOpType.isBranchInvert(func)

  io.result := aluRes
  io.taken := taken
  io.mispredict := (io.pred_taken ^ taken) && io.isBranch
}

class Alu(implicit p: Parameters) extends FunctionUnit with HasRedirectOut {

  val (src1, src2, func, pc, uop) = (
    io.in.bits.src(0),
    io.in.bits.src(1),
    io.in.bits.uop.ctrl.fuOpType,
    SignExt(io.in.bits.uop.cf.pc, AddrBits),
    io.in.bits.uop
  )

  val valid = io.in.valid
  val isBranch = ALUOpType.isBranch(func)
  val dataModule = Module(new AluDataModule)

  dataModule.io.src1 := src1
  dataModule.io.src2 := src2
  dataModule.io.func := func
  dataModule.io.pred_taken := uop.cf.pred_taken
  dataModule.io.isBranch := isBranch

  redirectOutValid := io.out.valid && isBranch
  redirectOut := DontCare
  redirectOut.level := RedirectLevel.flushAfter
  redirectOut.roqIdx := uop.roqIdx
  redirectOut.ftqIdx := uop.cf.ftqPtr
  redirectOut.ftqOffset := uop.cf.ftqOffset
  redirectOut.cfiUpdate.isMisPred := dataModule.io.mispredict
  redirectOut.cfiUpdate.taken := dataModule.io.taken
  redirectOut.cfiUpdate.predTaken := uop.cf.pred_taken

  io.in.ready := io.out.ready
  io.out.valid := valid
  io.out.bits.uop <> io.in.bits.uop
  io.out.bits.data := dataModule.io.result
}
