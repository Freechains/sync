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
    val end:   (Int) -> Unit
)

val CBS = CBs (
    { _ -> Unit },
    { _,_,_ -> Unit },
    { _ -> Unit }
)

class Sync (store: Store, cbs: CBs) {
    val store = store
    val cbs   = cbs

    init {
        store.cbs.add { v1,v2,v3 ->
            if (v1 == "chains") {
                when {
                    (v3 == "REM") -> main_cli(arrayOf(store.port_, "chains", "leave", v2))
                    (v3 == "ADD") -> main_cli(arrayOf(store.port_, "chains", "join", v2))
                    else          -> main_cli(arrayOf(store.port_, "chains", "join", v2, v3))
                }
            }
        }
        store.cbs.add { v1,v2,v3 ->
            if (v3 != "REM") {
                when (v1) {
                    "chains" -> thread { this.sync(this.store.getKeys("peers"), listOf(v2), listOf("recv")) }
                    "peers"  -> thread { this.sync(listOf(v2), this.store.getKeys("chains"), listOf("recv", "send")) }
                }
            }
        }
    }

    fun loop () {
        thread {
            this.sync_all()
        }
        val socket = Socket("localhost", store.port)
        val writer = DataOutputStream(socket.getOutputStream()!!)
        val reader = DataInputStream(socket.getInputStream()!!)
        writer.writeLineX("$PRE chains listen")
        while (true) {
            val (_,chain) = reader.readLineX().listSplit()
            thread {
                this.sync(this.store.getKeys("peers"), listOf(chain), listOf("send"))
            }
        }
    }

    fun sync_all () {
        this.sync(this.store.getKeys("peers"), this.store.getKeys("chains"), listOf("recv","send"))
    }

    private fun sync (peers: List<String>, chains: List<String>, actions: List<String>) {
        val n = chains.size * peers.size * actions.size
        this.cbs.start(n)
        chains.map { chain ->
            thread {  // 1 thread for each chain (rest is sequential b/c of per-chain lock in the protocol)
                for (action in actions) {
                    for (peer in peers) {
                        val ret = main_cli(arrayOf(this.store.port_, "peer", peer, action, chain))
                        this.cbs.item(chain,action,ret)
                    }
                }
            }
        }.map {
            it.join()
        }
        this.cbs.end(n)
    }
}