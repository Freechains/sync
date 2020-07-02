package org.freechains.sync

import org.freechains.cli.main_cli
import org.freechains.common.*
import org.freechains.cli.main_cli_assert
import org.freechains.store.Store
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlin.concurrent.thread

data class CBs (
    val start: (Int) -> Unit,
    val item:  (String,String,Pair<Boolean,String>) -> Unit,
    val end:   () -> Unit
)

val CBS = CBs (
    { _ -> Unit },
    { _,_,_ -> Unit },
    {}
)

/*
fun allIps () : List<String> {
    return NetworkInterface
            .getNetworkInterfaces()
            .toList()
            .map {
                it.inetAddresses.toList().map {
                    it.hostAddress
                }
            }
            .flatten()
}
fun ip2name () : String {
    val address = InetAddress.getByName("www.example.com")
    return address.hostAddress
}
 */

class Sync (store: Store, cbs: CBs) {
    val store = store
    val cbs   = cbs
    var actives = 0

    init {
        store.cbs.add { v1,v2,v3 ->
            if (v1 == "chains") {
                when {
                    (v3 == "REM") -> main_cli(arrayOf(store.port_, "chains", "leave", v2))
                    (v3 == "ADD") -> main_cli(arrayOf(store.port_, "chains", "join",  v2))
                    else          -> main_cli(arrayOf(store.port_, "chains", "join",  v2, v3))
                }
            }
        }
        store.cbs.add { v1,v2,v3 ->
            if (v3 != "REM") {
                //println("$v1 $v2 $v3")
                when (v1) {
                    "chains" -> thread { this.sync(this.store.getKeys("peers"), listOf(v2), listOf("recv")) }
                    "peers"  -> thread { this.sync(listOf(v2), this.store.getKeys("chains"), listOf("recv","send")) }
                }
            }
        }
        thread {
            val socket = Socket("localhost", store.port)
            val writer = DataOutputStream(socket.getOutputStream()!!)
            val reader = DataInputStream(socket.getInputStream()!!)
            writer.writeLineX("$PRE chains listen")
            while (true) {
                val (_,chain) = reader.readLineX().listSplit()
                //println(">>> $name")
                thread {
                    this.sync(this.store.getKeys("peers"), listOf(chain), listOf("send"))
                }
            }
        }
    }

    fun sync_all () {
        this.sync(this.store.getKeys("peers"), this.store.getKeys("chains"), listOf("recv","send"))
    }

    //@Synchronized
    private fun sync (peers: List<String>, chains: List<String>, actions: List<String>) {
        //println(">>> [${this.actives}] ($peers // $chains // $actions")
        //this.actives++
        this.cbs.start(chains.size * peers.size * actions.size)
        //Thread.sleep(200)

        // remove myself from peers
        /*
        val mes = allIps().map { Addr_Port(it, this.store.port) }
        val peers2 = peers
            .map    { it.to_Addr_Port() }
            .filter { ! mes.contains(it) }
            .map    { it.from_Addr_Port() }
        peers.toSet().intersect(peers2).let {
            //assert_(it.size == 0) { it }
        }
         */

        chains.map { chain ->
            thread {  // 1 thread for each chain (rest is sequential b/c of per-chain lock in the protocol)
                for (action in actions) {
                    for (peer in peers) {
                        //println(">>> [${this.store.port}/$actives] $action $chain to $peer")
                        val ret = main_cli(arrayOf(this.store.port_, "peer", peer, action, chain))
                        this.cbs.item(chain,action,ret)
                        //println("--- [${this.actives}] ($peers // $chains // $actions // $ret")
                        //println("<<< [${this.store.port}/$actives] $action $chain to $peer")
                    }
                }
            }
        }.map {
            it.join()
        }

        this.cbs.end()
        //this.actives--
        //println("<<< [${this.actives}] ($peers // $chains // $actions")
    }
}