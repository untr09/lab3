package referencechip

import Chisel._
import Node._
import uncore._
import rocket._
import DRAMModel._
import DRAMModel.MemModelConstants._

class FPGAOuterMemorySystem(htif_width: Int)(implicit conf: UncoreConfiguration) extends Module
{
  implicit val (tl, ln, l2, mif) = (conf.tl, conf.tl.ln, conf.l2, conf.mif)
  val io = new Bundle {
    val tiles = Vec.fill(conf.nTiles){new TileLinkIO}.flip
    val htif = (new TileLinkIO).flip
    val incoherent = Vec.fill(ln.nClients){Bool()}.asInput
    val mem = new MemIO
  }

  val masterEndpoints = (0 until ln.nMasters).map(i => Module(new L2CoherenceAgent(i)))

  val net = Module(new ReferenceChipCrossbarNetwork)
  net.io.clients zip (io.tiles :+ io.htif) map { case (net, end) => net <> end }
  net.io.masters zip (masterEndpoints.map(_.io.client)) map { case (net, end) => net <> end }
  masterEndpoints.map{ _.io.incoherent zip io.incoherent map { case (m, c) => m := c } }

  val conv = Module(new MemIOUncachedTileLinkIOConverter(2))
  if(ln.nMasters > 1) {
    val arb = Module(new UncachedTileLinkIOArbiterThatAppendsArbiterId(ln.nMasters))
    arb.io.in zip masterEndpoints.map(_.io.master) map { case (arb, cache) => arb <> cache }
    conv.io.uncached <> arb.io.out
  } else {
    conv.io.uncached <> masterEndpoints.head.io.master
  }
  io.mem.req_cmd <> Queue(conv.io.mem.req_cmd)
  io.mem.req_data <> Queue(conv.io.mem.req_data, tl.dataBits/mif.dataBits)
  conv.io.mem.resp <> Queue(io.mem.resp)
}

class FPGAUncore(htif_width: Int)(implicit conf: UncoreConfiguration) extends Module
{
  implicit val (tl, ln, mif) = (conf.tl, conf.tl.ln, conf.mif)
  val io = new Bundle {
    val host = new HostIO(htif_width)
    val mem = new MemIO
    val tiles = Vec.fill(conf.nTiles){new TileLinkIO}.flip
    val htif = Vec.fill(conf.nTiles){new HTIFIO(conf.nTiles)}.flip
    val incoherent = Vec.fill(conf.nTiles){Bool()}.asInput
  }
  val htif = Module(new HTIF(htif_width, CSRs.reset, conf.nSCR, conf.offsetBits))
  val outmemsys = Module(new FPGAOuterMemorySystem(htif_width))
  val incoherentWithHtif = (io.incoherent :+ Bool(true).asInput)
  outmemsys.io.incoherent := incoherentWithHtif
  htif.io.cpu <> io.htif
  outmemsys.io.mem <> io.mem

  // Add networking headers and endpoint queues
  def convertAddrToBank(addr: Bits): UInt = {
    require(conf.bankIdLsb + log2Up(conf.nBanks) < conf.mif.addrBits, {println("Invalid bits for bank multiplexing.")})
    addr(conf.bankIdLsb + log2Up(conf.nBanks) - 1, conf.bankIdLsb)
  }

  (outmemsys.io.tiles :+ outmemsys.io.htif).zip(io.tiles :+ htif.io.mem).zipWithIndex.map { 
    case ((outer, client), i) => 
      outer.acquire <> Queue(TileLinkHeaderOverwriter(client.acquire, i, conf.nBanks, convertAddrToBank _))
      outer.release <> Queue(TileLinkHeaderOverwriter(client.release, i, conf.nBanks, convertAddrToBank _))
      outer.grant_ack <> Queue(TileLinkHeaderOverwriter(client.release, i))
      client.grant <> Queue(outer.grant, 1, pipe = true)
      client.probe <> Queue(outer.probe)
  }

  htif.io.host.out <> io.host.out
  htif.io.host.in <> io.host.in
}

import MemoryConstants._
import TileLinkSizeConstants._

class ReferenceChip(htif_width: Int)(implicit mif: MemoryIFConfiguration) extends Module {
  val io = new Bundle {
    val host_in = new DecoupledIO(new HostPacket(htif_width)).flip()
    val host_out = new DecoupledIO(new HostPacket(htif_width))
    val host_clk = Bool(OUTPUT)
    val host_clk_edge = Bool(OUTPUT)
    val host_debug_stats_pcr = Bool(OUTPUT)
    val mem_req_cmd = new DecoupledIO(new MemReqCmd())
    val mem_req_data = new DecoupledIO(new MemData())
    val mem_resp = (new DecoupledIO(new MemResp())).flip()
  }
  
  val co = new MESICoherence
  val ntiles = 1
  val nbanks = 1
  val nmshrs = 2
  implicit val ln = LogicalNetworkConfiguration(log2Up(ntiles)+1, nbanks, ntiles+1)
  implicit val as = AddressSpaceConfiguration(PADDR_BITS, VADDR_BITS, PGIDX_BITS, ASID_BITS, PERM_BITS)
  implicit val tl = TileLinkConfiguration(co = co, ln = ln,
                                          addrBits = as.paddrBits-OFFSET_BITS, 
                                          clientXactIdBits = log2Up(1+8), 
                                          masterXactIdBits = 2*log2Up(2*1+1), 
                                          dataBits = CACHE_DATA_SIZE_IN_BYTES*8, 
                                          writeMaskBits = WRITE_MASK_BITS, 
                                          wordAddrBits = SUBWORD_ADDR_BITS, 
                                          atomicOpBits = ATOMIC_OP_BITS)
  implicit val l2 = L2CoherenceAgentConfiguration(tl, 1, 8)
  implicit val uc = UncoreConfiguration(l2, tl, mif, ntiles, nbanks, bankIdLsb = 5, nSCR = 64, offsetBits = OFFSET_BITS)

  val ic = ICacheConfig(64, 1, ntlb = 4, tl = tl, as = as, btb = BTBConfig(as, 8))
  val dc = DCacheConfig(64, 1, ntlb = 4, nmshr = 2, nrpq = 16, nsdq = 17, tl = tl, as = as, reqtagbits = -1, databits = -1)
  val rc = RocketConfiguration(tl, as, ic, dc, fpu = None,
                               fastMulDiv = false)

  val resetSigs = Vec.fill(uc.nTiles){Bool()}
  val tileList = (0 until uc.nTiles).map(r => Module(new Tile(resetSignal = resetSigs(r))(rc)))
  val uncore = Module(new FPGAUncore(htif_width))

  for (i <- 0 until uc.nTiles) {
    val hl = uncore.io.htif(i)
    val tl = uncore.io.tiles(i)
    val il = uncore.io.incoherent(i)

    resetSigs(i) := hl.reset
    val tile = tileList(i)

    tile.io.tilelink <> tl
    il := hl.reset
    tile.io.host.id := UInt(i)
    tile.io.host.reset := Reg(next=Reg(next=hl.reset))
    tile.io.host.pcr_req <> Queue(hl.pcr_req)
    hl.pcr_rep <> Queue(tile.io.host.pcr_rep)
    hl.ipi_req <> Queue(tile.io.host.ipi_req)
    tile.io.host.ipi_rep <> Queue(hl.ipi_rep)
  }
  
  io.host_in.ready := uncore.io.host.in.ready
  uncore.io.host.in.bits := io.host_in.bits.data
  uncore.io.host.in.valid := io.host_in.valid
  
  uncore.io.host.out.ready := io.host_out.ready
  io.host_out.bits.data := uncore.io.host.out.bits
  io.host_out.valid := uncore.io.host.out.valid
  
  io.host_clk := uncore.io.host.clk
  io.host_clk_edge := uncore.io.host.clk_edge
  io.host_debug_stats_pcr := uncore.io.host.debug_stats_pcr

  io.mem_req_cmd <> uncore.io.mem.req_cmd
  io.mem_req_data <> uncore.io.mem.req_data
  io.mem_resp <> uncore.io.mem.resp
}

import MemoryConstants._

class FPGATopIO(htifWidth: Int)(implicit conf: MemoryIFConfiguration) extends TopIO(htifWidth)(conf)

class FPGATop extends Module {
  val htif_width = 16
  
  implicit val mif = MemoryIFConfiguration(MEM_ADDR_BITS, MEM_DATA_BITS, MEM_TAG_BITS, 4)
  val deviceWidth = ROW_WIDTH/mif.dataBits
  implicit val mc = MemoryControllerConfiguration(deviceWidth, (if(deviceWidth == 4) 0 else log2Up(deviceWidth/4)), mif)

  val io = new FPGATopIO(htif_width)
  
  val referenceChip = Module(new Fame1Wrapper(new ReferenceChip(htif_width)))
  val dramModel = Module(new DRAMSystemWrapper())
  //dram model parameters setup
  dramModel.io.params.tRAS := UInt(4)
  dramModel.io.params.tRCD := UInt(4)
  dramModel.io.params.tRP := UInt(4)
  dramModel.io.params.tCCD := UInt(4)
  dramModel.io.params.tRTP := UInt(4)
  dramModel.io.params.tWTR := UInt(4)
  dramModel.io.params.tWR := UInt(4)
  dramModel.io.params.tRRD := UInt(4)
  
  //host to reference chip connections
  referenceChip.DecoupledIOs("host_in").host_valid := Bool(true)
  referenceChip.DecoupledIOs("host_in").target.bits := io.host.in.bits
  referenceChip.DecoupledIOs("host_in").target.valid := io.host.in.valid
  io.host.in.ready := referenceChip.DecoupledIOs("host_in").host_ready && referenceChip.DecoupledIOs("host_in").target.ready

  io.host.out.valid := referenceChip.DecoupledIOs("host_out").host_valid && referenceChip.DecoupledIOs("host_out").target.valid
  io.host.out.bits := referenceChip.DecoupledIOs("host_out").target.bits
  referenceChip.DecoupledIOs("host_out").target.ready := io.host.out.ready
  referenceChip.DecoupledIOs("host_out").host_ready := Bool(true)
  
  io.host.clk := referenceChip.DebugIOs("host_clk")
  io.host.clk_edge := referenceChip.DebugIOs("host_clk_edge")
  io.host.debug_stats_pcr := referenceChip.DebugIOs("host_debug_stats_pcr")

  //reference chip to dram model connections
  val mem_req_cmd_queue = Module(new FameQueue(8)(new MemReqCmd()))
  val mem_req_data_queue = Module(new FameQueue(8)(new MemData()))
  val mem_resp_queue = Module(new FameQueue(8)(new MemResp()))
  
  //cmd queue
  FameDecoupledIO.connect(referenceChip.DecoupledIOs("mem_req_cmd"),  mem_req_cmd_queue.io.enq, new MemReqCmd)
  mem_req_cmd_queue.io.deq <> dramModel.io.memReqCmd
  
  //data queue
  FameDecoupledIO.connect(referenceChip.DecoupledIOs("mem_req_data"), mem_req_data_queue.io.enq, new MemData)
  mem_req_data_queue.io.deq <> dramModel.io.memReqData
  
  //resp queue
  mem_resp_queue.io.enq <> dramModel.io.memResp
  FameDecoupledIO.connect(referenceChip.DecoupledIOs("mem_resp"), mem_resp_queue.io.deq, new MemResp)
  
  //dram model to outside memory connections
  val host_mem_cmd_queue = Module(new Queue(new MemReqCmd, 2))
  val host_mem_data_queue = Module(new Queue(new MemData, mif.dataBeats))
  val host_mem_resp_queue = Module(new Queue(new MemResp, mif.dataBeats))
  
  host_mem_cmd_queue.io.enq <> dramModel.io.mem.req_cmd
  host_mem_cmd_queue.io.deq <> io.mem.req_cmd
  
  host_mem_data_queue.io.enq <> dramModel.io.mem.req_data
  host_mem_data_queue.io.deq <> io.mem.req_data
  
  host_mem_resp_queue.io.enq <> io.mem.resp
  host_mem_resp_queue.io.deq <> dramModel.io.mem.resp
}

abstract class AXISlave extends Module {
  val aw = 5
  val dw = 32
  val io = new Bundle {
    val in = Decoupled(Bits(width = dw)).flip
    val out = Decoupled(Bits(width = dw))
    val addr = Bits(INPUT, aw)
  }
}

class Slave extends AXISlave
{
  val top = Module(new FPGATop)

  val memw = top.io.mem.resp.bits.data.getWidth
  val htifw = top.io.host.in.bits.getWidth
  
  val n = 4
  def wen(i: Int) = io.in.valid && io.addr(log2Up(n)-1,0) === UInt(i)
  def ren(i: Int) = io.out.ready && io.addr(log2Up(n)-1,0) === UInt(i)
  val rdata = Vec.fill(n){Bits(width = dw)}
  val rvalid = Vec.fill(n){Bool()}
  val wready = Vec.fill(n){Bool()}

  io.in.ready := wready(io.addr)
  io.out.valid := rvalid(io.addr)
  io.out.bits := rdata(io.addr)

  // write r0 -> htif.in (blocking)
  wready(0) := top.io.host.in.ready
  top.io.host.in.valid := wen(0)
  top.io.host.in.bits := io.in.bits

  // read cr0 -> htif.out (nonblocking)
  rdata(0) := Cat(top.io.host.out.bits, top.io.host.out.valid)
  rvalid(0) := Bool(true)
  top.io.host.out.ready := ren(0)
  require(dw >= htifw + 1)

  // read cr1 -> mem.req_cmd (nonblocking)
  // the memory system is FIFO from hereon out, so just remember the tags here
  val tagq = Module(new Queue(top.io.mem.req_cmd.bits.tag, 4))
  tagq.io.enq.bits := top.io.mem.req_cmd.bits.tag
  tagq.io.enq.valid := ren(1) && top.io.mem.req_cmd.valid && !top.io.mem.req_cmd.bits.rw
  top.io.mem.req_cmd.ready := ren(1)
  rdata(1) := Cat(top.io.mem.req_cmd.bits.addr, top.io.mem.req_cmd.bits.rw, top.io.mem.req_cmd.valid && (tagq.io.enq.ready || top.io.mem.req_cmd.bits.rw))
  rvalid(1) := Bool(true)
  require(dw >= top.io.mem.req_cmd.bits.addr.getWidth + 1 + 1)

  // write cr1 -> mem.resp (nonblocking)
  val in_count = Reg(init=UInt(0, log2Up(memw/dw)))
  val rf_count = Reg(init=UInt(0, log2Up(CACHE_DATA_SIZE_IN_BYTES*8/memw)))
  require(memw % dw == 0 && isPow2(memw/dw))
  val in_reg = Reg(top.io.mem.resp.bits.data)
  top.io.mem.resp.bits.data := Cat(io.in.bits, in_reg(in_reg.getWidth-1,dw))
  top.io.mem.resp.bits.tag := tagq.io.deq.bits
  top.io.mem.resp.valid := wen(1) && in_count.andR
  tagq.io.deq.ready := top.io.mem.resp.fire() && rf_count.andR
  wready(1) := top.io.mem.resp.ready
  when (wen(1) && wready(1)) {
    in_count := in_count + UInt(1)
    in_reg := top.io.mem.resp.bits.data
  }
  when (top.io.mem.resp.fire()) {
    rf_count := rf_count + UInt(1)
  }

  // read cr2 -> mem.req_data (blocking)
  val out_count = Reg(init=UInt(0, log2Up(memw/dw)))
  top.io.mem.req_data.ready := ren(2) && out_count.andR
  rdata(2) := top.io.mem.req_data.bits.data >> (out_count * UInt(dw))
  rvalid(2) := top.io.mem.req_data.valid
  when (ren(2) && rvalid(2)) { out_count := out_count + UInt(1) }

  // read cr3 -> debug signals (nonblocking)
  rdata(3) := Cat(top.io.mem.req_cmd.valid, tagq.io.enq.ready)
  rvalid(3) := Bool(true)

  // writes to cr2, cr3 ignored
  wready(2) := Bool(true)
  wready(3) := Bool(true)
}
