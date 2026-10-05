// PUCRS - Escola Politécnica - Sistemas Operacionais
// Prof. Fernando Dotti
// Código fornecido como parte da solução do projeto de Sistemas Operacionais
//
// Trabalho T1 = T1-A (gerente de memória paginada)
//             + T1-B (gerente de processos)
//             + T1-C (escalonamento round-robin contínuo, multithreaded)
//
// Estrutura deste código:
//    Todo código está dentro da classe *Sistema*
//    Dentro de Sistema, encontra-se acima a definição de HW:
//           Memory,  Word,
//           CPU tem Opcodes (codigos de operacoes suportadas na cpu),
//               e Interrupcoes possíveis, define o que executa para cada instrucao.
//               A CPU agora traduz todo endereço lógico para físico usando a
//               tabela de páginas do processo, e gera interrupção de relógio
//               a cada "delta" instruções.
//           VM -  a máquina virtual é uma instanciação de CPU e Memória
//    Depois as definições de SW (SO):
//           InterruptHandling, SysCallHandling,
//           GM  - gerente de memória (paginação)
//           PCB - descritor de processo
//           GP  - gerente de processos
//           Escalonador (thread) e CPU (thread)
//           Shell (thread main) - interpreta comandos do usuário
//    Por último os programas existentes, que podem ser copiados em memória.
//           Isto representa programas armazenados.
//
// Uso:  java Sistema [tamMem] [tamPg] [delta]
//       padrão: tamMem=1024, tamPg=8, delta=10

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

public class Sistema {

	// -------------------------------------------------------------------------------------------------------
	// --------------------- H A R D W A R E - definicoes de HW
	// ----------------------------------------------

	// -------------------------------------------------------------------------------------------------------
	// --------------------- M E M O R I A - definicoes de palavra de memoria,
	// memória ----------------------

	public class Memory {
		public Word[] pos; // pos[i] é a posição i da memória. cada posição é uma palavra.

		public Memory(int size) {
			pos = new Word[size];
			for (int i = 0; i < pos.length; i++) {
				pos[i] = new Word(Opcode.___, -1, -1, -1);
			}
			; // cada posicao da memoria inicializada
		}
	}

	public class Word {    // cada posicao da memoria tem uma instrucao (ou um dado)
		public Opcode opc; //
		public int ra;     // indice do primeiro registrador da operacao (Rs ou Rd cfe opcode na tabela)
		public int rb;     // indice do segundo registrador da operacao (Rc ou Rs cfe operacao)
		public int p;      // parametro para instrucao (k ou A cfe operacao), ou o dado, se opcode = DADO

		public Word(Opcode _opc, int _ra, int _rb, int _p) { // vide definição da VM - colunas vermelhas da tabela
			opc = _opc;
			ra = _ra;
			rb = _rb;
			p  = _p;
		}
	}

	// -------------------------------------------------------------------------------------------------------
	// --------------------- C P U - definicoes da CPU
	// -----------------------------------------------------

	public enum Opcode {
		DATA, ___,              // se memoria nesta posicao tem um dado, usa DATA, se nao usada ee NULO ___
		JMP, JMPI, JMPIG, JMPIL, JMPIE, // desvios
		JMPIM, JMPIGM, JMPILM, JMPIEM,
		JMPIGK, JMPILK, JMPIEK, JMPIGT,
		ADDI, SUBI, ADD, SUB, MULT,    // matematicos
		LDI, LDD, STD, LDX, STX, MOVE, // movimentacao
		SYSCALL, STOP                  // chamada de sistema e parada
	}

	public enum Interrupts {           // possiveis interrupcoes que esta CPU gera
		noInterrupt, intEnderecoInvalido, intInstrucaoInvalida, intOverflow,
		intTimer,                      // fim da fatia de tempo (relógio = contador de instruções)
		intProcessoRemovido;           // usuário pediu "rm" do processo que está na CPU
	}

	public class CPU {
		private int maxInt; // valores maximo e minimo para inteiros nesta cpu
		private int minInt;
		                    // CONTEXTO da CPU ...
		private int pc;     // ... composto de program counter (endereço LÓGICO),
		private Word ir;    // instruction register,
		private int[] reg;  // registradores da CPU
		private Interrupts irpt; // durante instrucao, interrupcao pode ser sinalizada
		private int[] tabPag;    // tabela de páginas do processo em execução (MMU)
		                    // FIM CONTEXTO DA CPU

		private Word[] m;   // m é o array de memória "física", CPU tem uma ref a m para acessar
		private int tamPg;  // tamanho de página (= tamanho de frame)

		private InterruptHandling ih;    // significa desvio para rotinas de tratamento de Int - se int ligada, desvia
		private SysCallHandling sysCall; // significa desvio para tratamento de chamadas de sistema

		                            // relógio: a cada "delta" instruções liga interrupção de timer
		private int delta;
		private int contInstr;

		private boolean ativa;                 // true enquanto há processo despachado na CPU
		private volatile boolean pedidoRemocao; // "rm" do processo em execução
		private int pendenteIN = -1;           // end. físico aguardando leitura (SYSCALL IN), -1 se nenhum
		private volatile int atraso = 0;       // ms entre instruções (para acompanhar a execução)

		                            // auxilio aa depuração
		private volatile boolean debug; // se true entao mostra cada instrucao em execucao
		private Utilities u;        // para debug (dump)

		public CPU(Memory _mem, int _tamPg, int _delta, boolean _debug) { // ref a MEMORIA passada na criacao da CPU
			maxInt = 32767;            // capacidade de representacao modelada
			minInt = -32767;           // se exceder deve gerar interrupcao de overflow
			m = _mem.pos;              // usa o atributo 'm' para acessar a memoria, só para ficar mais pratico
			reg = new int[10];         // aloca o espaço dos registradores - regs 8 e 9 usados somente para IO
			tamPg = _tamPg;
			delta = _delta;
			debug = _debug;            // se true, print da instrucao em execucao
		}

		public void setAddressOfHandlers(InterruptHandling _ih, SysCallHandling _sysCall) {
			ih = _ih;                  // aponta para rotinas de tratamento de int
			sysCall = _sysCall;        // aponta para rotinas de tratamento de chamadas de sistema
		}

		public void setUtilities(Utilities _u) {
			u = _u;                     // aponta para rotinas utilitárias - fazer dump da memória na tela
		}

		// MMU: traducao de endereco logico -> fisico, com controle de acesso indevido.
		// retorna o endereço físico, ou -1 (e liga intEnderecoInvalido) se o endereço
		// não pertence a nenhuma página do processo
		private int traduz(int e) {
			if (tabPag != null && e >= 0) {
				int pagina = e / tamPg;
				int deslocamento = e % tamPg;
				if (pagina < tabPag.length) {
					return tabPag[pagina] * tamPg + deslocamento;
				}
			}
			irpt = Interrupts.intEnderecoInvalido;    // liga interrupcao no meio da exec da instrucao
			return -1;
		}

		private boolean testOverflow(int v) {             // toda operacao matematica deve avaliar se ocorre overflow
			if ((v < minInt) || (v > maxInt)) {
				irpt = Interrupts.intOverflow;            // se houver liga interrupcao no meio da exec da instrucao
				return false;
			}
			;
			return true;
		}

		// restaura o contexto de um processo na CPU (feito pelo escalonador)
		public void setContext(int _pc, int[] _reg, int[] _tabPag) {
			pc = _pc;
			System.arraycopy(_reg, 0, reg, 0, reg.length);
			tabPag = _tabPag;
			irpt = Interrupts.noInterrupt;                // reset da interrupcao registrada
			contInstr = 0;                                // reinicia a fatia de tempo
			pendenteIN = -1;
			ativa = true;
		}

		// salva o contexto da CPU no PCB (feito pelas rotinas de tratamento)
		public void saveContext(PCB p) {
			p.pc = pc;
			System.arraycopy(reg, 0, p.reg, 0, reg.length);
		}

		// um ciclo de instrução: fetch, execução e verificação de interrupção
		// deve ser chamado com o lock do SO adquirido
		private void passo() {
			if (pedidoRemocao) {                          // "rm" do processo rodando
				pedidoRemocao = false;
				ih.handle(Interrupts.intProcessoRemovido);
				return;
			}
			// --------------------------------------------------------------------------------------------------
			// FASE DE FETCH
			int pcFis = traduz(pc);
			if (pcFis >= 0) { // pc valido
				ir = m[pcFis];  // <<<<<<<<<<<< AQUI faz FETCH - busca posicao da memoria apontada por pc, guarda em ir
				             // resto é dump de debug
				if (debug) {
					System.out.print("                                              regs: ");
					for (int i = 0; i < 10; i++) {
						System.out.print(" r[" + i + "]:" + reg[i]);
					}
					;
					System.out.println();
				}
				if (debug) {
					System.out.print("          [P" + sysCall.idRodando() + "] pc: " + pc + " (fis " + pcFis + ")   exec: ");
					u.dump(ir);
				}

			// --------------------------------------------------------------------------------------------------
			// FASE DE EXECUCAO DA INSTRUCAO CARREGADA NO ir
				int f; // endereço físico calculado na instrução
				switch (ir.opc) {       // conforme o opcode (código de operação) executa

					// Instrucoes de Busca e Armazenamento em Memoria
					case LDI: // Rd ← k        veja a tabela de instrucoes do HW simulado para entender a semantica da instrucao
						reg[ir.ra] = ir.p;
						pc++;
						break;
					case LDD: // Rd <- [A]
						f = traduz(ir.p);
						if (f >= 0) {
							reg[ir.ra] = m[f].p;
							pc++;
						}
						break;
					case LDX: // RD <- [RS] // NOVA
						f = traduz(reg[ir.rb]);
						if (f >= 0) {
							reg[ir.ra] = m[f].p;
							pc++;
						}
						break;
					case STD: // [A] ← Rs
						f = traduz(ir.p);
						if (f >= 0) {
							m[f].opc = Opcode.DATA;
							m[f].p = reg[ir.ra];
							pc++;
							if (debug)
							    {   System.out.print("                                  " + f + ":  ");
								    u.dump(m[f]);
								}
							}
						break;
					case STX: // [Rd] ←Rs
						f = traduz(reg[ir.ra]);
						if (f >= 0) {
							m[f].opc = Opcode.DATA;
							m[f].p = reg[ir.rb];
							pc++;
						}
						;
						break;
					case MOVE: // RD <- RS
						reg[ir.ra] = reg[ir.rb];
						pc++;
						break;
					// Instrucoes Aritmeticas
					case ADD: // Rd ← Rd + Rs
						reg[ir.ra] = reg[ir.ra] + reg[ir.rb];
						testOverflow(reg[ir.ra]);
						pc++;
						break;
					case ADDI: // Rd ← Rd + k
						reg[ir.ra] = reg[ir.ra] + ir.p;
						testOverflow(reg[ir.ra]);
						pc++;
						break;
					case SUB: // Rd ← Rd - Rs
						reg[ir.ra] = reg[ir.ra] - reg[ir.rb];
						testOverflow(reg[ir.ra]);
						pc++;
						break;
					case SUBI: // RD <- RD - k // NOVA
						reg[ir.ra] = reg[ir.ra] - ir.p;
						testOverflow(reg[ir.ra]);
						pc++;
						break;
					case MULT: // Rd <- Rd * Rs
						reg[ir.ra] = reg[ir.ra] * reg[ir.rb];
						testOverflow(reg[ir.ra]);
						pc++;
						break;

					// Instrucoes JUMP
					case JMP: // PC <- k
						pc = ir.p;
						break;
					case JMPIM: // PC <- [A]
						f = traduz(ir.p);
						if (f >= 0) {
							pc = m[f].p;
						}
						break;
					case JMPIG: // If Rc > 0 Then PC ← Rs Else PC ← PC +1
						if (reg[ir.rb] > 0) {
							pc = reg[ir.ra];
						} else {
							pc++;
						}
						break;
					case JMPIGK: // If RC > 0 then PC <- k else PC++
						if (reg[ir.rb] > 0) {
							pc = ir.p;
						} else {
							pc++;
						}
						break;
					case JMPILK: // If RC < 0 then PC <- k else PC++
						if (reg[ir.rb] < 0) {
							pc = ir.p;
						} else {
							pc++;
						}
						break;
					case JMPIEK: // If RC = 0 then PC <- k else PC++
						if (reg[ir.rb] == 0) {
							pc = ir.p;
						} else {
							pc++;
						}
						break;
					case JMPIL: // if Rc < 0 then PC <- Rs Else PC <- PC +1
						if (reg[ir.rb] < 0) {
							pc = reg[ir.ra];
						} else {
							pc++;
						}
						break;
					case JMPIE: // If Rc = 0 Then PC <- Rs Else PC <- PC +1
						if (reg[ir.rb] == 0) {
							pc = reg[ir.ra];
						} else {
							pc++;
						}
						break;
					case JMPIGM: // If RC > 0 then PC <- [A] else PC++
						f = traduz(ir.p);
					    if (f >= 0){
						    if (reg[ir.rb] > 0) {
							   pc = m[f].p;
						    } else {
							  pc++;
						   }
					    }
						break;
					case JMPILM: // If RC < 0 then PC <- [A] else PC++
						if (reg[ir.rb] < 0) {
							f = traduz(ir.p);
							if (f >= 0) {
								pc = m[f].p;
							}
						} else {
							pc++;
						}
						break;
					case JMPIEM: // If RC = 0 then PC <- [A] else PC++
						if (reg[ir.rb] == 0) {
							f = traduz(ir.p);
							if (f >= 0) {
								pc = m[f].p;
							}
						} else {
							pc++;
						}
						break;
					case JMPIGT: // If RS>RC then PC <- k else PC++
						if (reg[ir.ra] > reg[ir.rb]) {
							pc = ir.p;
						} else {
							pc++;
						}
						break;

					case DATA: // pc está sobre área supostamente de dados
						irpt = Interrupts.intInstrucaoInvalida;
						break;

					// Chamadas de sistema
					case SYSCALL:
						sysCall.handle(); // <<<<< aqui desvia para rotina de chamada de sistema, no momento so
											// temos IO
						pc++;
						break;

					case STOP: // chamada de sistema: fim do processo
						sysCall.stop();
						break;

					// Inexistente
					default:
						irpt = Interrupts.intInstrucaoInvalida;
						break;
				}
			}
			contInstr++;
			// --------------------------------------------------------------------------------------------------
			// VERIFICA INTERRUPÇÃO !!! - TERCEIRA FASE DO CICLO DE INSTRUÇÕES
			if (irpt != Interrupts.noInterrupt) {     // existe interrupção
				ih.handle(irpt);                      // desvia para rotina de tratamento - esta rotina é do SO
			} else if (ativa && pendenteIN < 0 && contInstr >= delta) {
				ih.handle(Interrupts.intTimer);       // completou a fatia: liga interrupção de relógio
			}
		}

		// ------------------------------------------------------------------------------------------------------
		// Thread CPU: aguarda bloqueada até o escalonador despachar um processo;
		// executa instruções enquanto o processo estiver na CPU; volta a aguardar.
		public void loopCPU() {
			while (true) {
				so.semaCPU.acquireUninterruptibly();          // aguarda bloqueada (semaCPU.wait)
				boolean continua = true;
				while (continua) {
					int endIN;
					so.lock.lock();
					try {
						if (ativa) {
							passo();
						}
						continua = ativa;
						endIN = pendenteIN;
					} finally {
						so.lock.unlock();
					}
					if (endIN >= 0) {                     // SYSCALL IN: aguarda o usuário FORA do lock,
						int v = so.leEntrada();           // para o shell continuar atendendo comandos
						so.lock.lock();
						try {
							m[endIN].opc = Opcode.DATA;
							m[endIN].p = v;
							pendenteIN = -1;
						} finally {
							so.lock.unlock();
						}
					}
					if (atraso > 0) {
						try { Thread.sleep(atraso); } catch (InterruptedException e) { }
					}
				}
			}
		}
	}
	// ------------------ C P U - fim
	// -----------------------------------------------------------------------
	// ------------------------------------------------------------------------------------------------------

	// ------------------- HW - constituido de CPU e MEMORIA
	// -----------------------------------------------
	public class HW {
		public Memory mem;
		public CPU cpu;

		public HW(int tamMem, int tamPg, int delta) {
			mem = new Memory(tamMem);
			cpu = new CPU(mem, tamPg, delta, false); // traceOn liga debug
		}
	}
	// -------------------------------------------------------------------------------------------------------

	// --------------------H A R D W A R E - fim
	// -------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------

	// ///////////////////////////////////////////////////////////////////////////////////////////////////////

	// -------------------------------------------------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------
	// ------------------- SW - inicio - Sistema Operacional
	// -------------------------------------------------

	// ------------------- I N T E R R U P C O E S - rotinas de tratamento
	// ----------------------------------
	public class InterruptHandling {
		private HW hw; // referencia ao hw se tiver que setar algo

		public InterruptHandling(HW _hw) {
			hw = _hw;
		}

		public void handle(Interrupts irpt) {
			if (irpt == Interrupts.intTimer) {
				so.rotTimer();                         // Rot Trat TIMER
			} else {                                   // Rot Tratamento: overflow, acesso indevido, etc.
				System.out.println("                                               Interrupcao " + irpt
						+ "   processo: " + so.running.id + "   pc: " + hw.cpu.pc);
				so.finalizaRunning();                  // finaliza processo e libera escalonador
			}
		}
	}

	// ------------------- C H A M A D A S D E S I S T E M A - rotinas de tratamento
	// ----------------------
	public class SysCallHandling {
		private HW hw; // referencia ao hw se tiver que setar algo

		public SysCallHandling(HW _hw) {
			hw = _hw;
		}

		public int idRodando() {
			return so.running == null ? -1 : so.running.id;
		}

		public void stop() { // chamada de sistema indicando final de programa
			if (so.log) System.out.println("                                               SYSCALL STOP   processo: " + idRodando());
			so.finalizaRunning();
		}

		public void handle() { // chamada de sistema
			                   // suporta somente IO, com parametros
							   // reg[8] = in ou out    e reg[9] endereco (lógico) do inteiro
			if (so.log) System.out.println("SYSCALL pars:  " + hw.cpu.reg[8] + " / " + hw.cpu.reg[9]);

			if  (hw.cpu.reg[8]==1){
				  // leitura - a CPU aguarda o valor do usuário e o escreve no endereço dado em reg[9]
				  int f = hw.cpu.traduz(hw.cpu.reg[9]);
				  if (f >= 0) {
					  hw.cpu.pendenteIN = f;
				  }
			} else if (hw.cpu.reg[8]==2){
				  // escrita - escreve o conteuodo da memoria na posicao dada em reg[9]
				  int f = hw.cpu.traduz(hw.cpu.reg[9]);
				  if (f >= 0) {
					  System.out.println("OUT [processo " + idRodando() + "]:   " + hw.mem.pos[f].p);
				  }
			} else {System.out.println("  PARAMETRO INVALIDO"); }
		}
	}

	// ------------------- G E R E N T E   D E   M E M O R I A  (paginação)
	// ----------------------------------
	public class GM {
		private int tamPg;          // tamanho de página = tamanho de frame
		private int nroFrames;      // tamMem / tamPg
		private boolean[] livre;    // livre[f] == true se o frame f está disponível

		public GM(int tamMem, int _tamPg) {
			tamPg = _tamPg;
			nroFrames = tamMem / tamPg;
			livre = new boolean[nroFrames];
			Arrays.fill(livre, true);
		}

		// retorna a tabela de páginas (posição i = frame que hospeda a página i),
		// ou null se não há frames livres suficientes
		public int[] aloca(int nroPalavras) {
			int nroPaginas = (nroPalavras + tamPg - 1) / tamPg;
			if (nroPaginas > framesLivres()) {
				return null;
			}
			int[] tabelaPaginas = new int[nroPaginas];
			int pg = 0;
			for (int f = 0; f < nroFrames && pg < nroPaginas; f++) {
				if (livre[f]) {
					livre[f] = false;
					tabelaPaginas[pg++] = f;
				}
			}
			return tabelaPaginas;
		}

		// simplesmente libera os frames alocados
		public void desaloca(int[] tabelaPaginas) {
			for (int f : tabelaPaginas) {
				livre[f] = true;
			}
		}

		public int framesLivres() {
			int n = 0;
			for (boolean b : livre) {
				if (b) n++;
			}
			return n;
		}

		public int inicioFrame(int f) { return f * tamPg; }
		public int fimFrame(int f)    { return (f + 1) * tamPg - 1; }
	}

	// ------------------- P C B
	// ----------------------------------
	public class PCB {
		public int id;
		public String nome;            // nome do programa
		public int tamanho;            // nro de palavras do programa
		public int[] tabelaPaginas;    // página i está no frame tabelaPaginas[i]
		public int pc = 0;             // contexto salvo da CPU
		public int[] reg = new int[10];
		public String estado;          // "pronto" ou "rodando"
		public boolean liberado;       // liberado para executar por exec/execAll (modo não contínuo)

		public PCB(int _id, String _nome, int _tamanho, int[] _tabelaPaginas) {
			id = _id;
			nome = _nome;
			tamanho = _tamanho;
			tabelaPaginas = _tabelaPaginas;
			estado = "pronto";
		}
	}

	// ------------------- G E R E N T E   D E   P R O C E S S O S
	// ----------------------------------
	public class GP {
		private int proxId = 1;

		// cria processo; retorna o id, ou -1 se não existe o programa ou não há memória
		public int criaProcesso(String nomePrograma) {
			Word[] programa = progs.retrieveProgram(nomePrograma);
			if (programa == null) {
				System.out.println("programa inexistente: " + nomePrograma);
				return -1;
			}
			int[] tabela = so.gm.aloca(programa.length);      // pede alocação ao GM
			if (tabela == null) {
				System.out.println("memoria insuficiente para " + nomePrograma);
				return -1;
			}
			so.utils.carga(programa, tabela);                // carrega o programa nos frames
			PCB p = new PCB(proxId++, nomePrograma, programa.length, tabela);
			so.ready.addLast(p);                             // coloca na fila de prontos
			if (so.running == null) {
				so.semaSch.release();                        // se não há processo rodando, libera o escalonador
			}
			return p.id;
		}

		// desaloca processo com id: retira de fila, libera memória e pcb.
		// se for o processo em execução, pede à CPU que o interrompa (o tratamento
		// da interrupção finaliza o processo e chama o escalonador)
		public boolean desalocaProcesso(int id) {
			if (so.running != null && so.running.id == id) {
				so.hw.cpu.pedidoRemocao = true;
				return true;
			}
			PCB p = busca(id);
			if (p == null) {
				return false;
			}
			so.ready.remove(p);
			libera(p);
			return true;
		}

		public void libera(PCB p) {
			so.gm.desaloca(p.tabelaPaginas);
			p.tabelaPaginas = new int[0];
			so.fimProcesso.signalAll();
		}

		public PCB busca(int id) {
			if (so.running != null && so.running.id == id) return so.running;
			for (PCB p : so.ready) {
				if (p.id == id) return p;
			}
			return null;
		}
	}

	// ------------------ U T I L I T A R I O S D O S I S T E M A
	// -----------------------------------------
	public class Utilities {
		private HW hw;

		public Utilities(HW _hw) {
			hw = _hw;
		}

		// carga: cada página i do programa é copiada para o frame tabelaPaginas[i]
		public void carga(Word[] p, int[] tabelaPaginas) {
			Word[] m = hw.mem.pos; // m[] é o array de posições memória do hw
			int tamPg = so.gm.tamPg;
			for (int f : tabelaPaginas) {   // limpa os frames (resto da última página)
				for (int i = so.gm.inicioFrame(f); i <= so.gm.fimFrame(f); i++) {
					m[i].opc = Opcode.___; m[i].ra = -1; m[i].rb = -1; m[i].p = -1;
				}
			}
			for (int i = 0; i < p.length; i++) {
				int fis = tabelaPaginas[i / tamPg] * tamPg + (i % tamPg);
				m[fis].opc = p[i].opc;
				m[fis].ra = p[i].ra;
				m[fis].rb = p[i].rb;
				m[fis].p = p[i].p;
			}
		}

		// dump da memória
		public void dump(Word w) { // funcoes de DUMP nao existem em hardware - colocadas aqui para facilidade
			System.out.print("[ ");
			System.out.print(w.opc);
			System.out.print(", ");
			System.out.print(w.ra);
			System.out.print(", ");
			System.out.print(w.rb);
			System.out.print(", ");
			System.out.print(w.p);
			System.out.println("  ] ");
		}

		public void dump(int ini, int fim) {
			Word[] m = hw.mem.pos; // m[] é o array de posições memória do hw
			ini = Math.max(0, ini);
			fim = Math.min(m.length, fim);
			for (int i = ini; i < fim; i++) {
				System.out.print(i);
				System.out.print(":  ");
				dump(m[i]);
			}
		}

		// dump do PCB e da memória (lógica) do processo
		public void dumpProcesso(PCB p) {
			int tamPg = so.gm.tamPg;
			System.out.println("PCB  id: " + p.id + "   programa: " + p.nome + "   estado: " + p.estado
					+ "   tamanho: " + p.tamanho + " palavras");
			System.out.println("     pc: " + p.pc + "   regs: " + Arrays.toString(p.reg));
			System.out.println("     tabela de paginas (pagina -> frame): " + Arrays.toString(p.tabelaPaginas));
			for (int pg = 0; pg < p.tabelaPaginas.length; pg++) {
				int f = p.tabelaPaginas[pg];
				System.out.println("  --- pagina " + pg + "  frame " + f
						+ "  [" + so.gm.inicioFrame(f) + ".." + so.gm.fimFrame(f) + "]");
				for (int d = 0; d < tamPg; d++) {
					int fis = f * tamPg + d;
					System.out.print("  " + (pg * tamPg + d) + " (fis " + fis + "):  ");
					dump(hw.mem.pos[fis]);
				}
			}
		}
	}

	// ------------------- S O
	// ----------------------------------
	public class SO {
		public HW hw;
		public InterruptHandling ih;
		public SysCallHandling sc;
		public Utilities utils;
		public GM gm;
		public GP gp;

		public PCB running = null;                          // processo rodando
		public LinkedList<PCB> ready = new LinkedList<>();  // fila de prontos

		public final ReentrantLock lock = new ReentrantLock(true);    // exclusão mútua das estruturas do SO
		public final Condition fimProcesso = lock.newCondition();      // sinaliza término de processo
		public final Semaphore semaSch = new Semaphore(0);             // libera o escalonador
		public final Semaphore semaCPU = new Semaphore(0);             // libera a CPU

		public volatile boolean modoContinuo = false;  // escalonamento contínuo (T1-C 3.2)
		public volatile boolean log = true;            // mostra atividades de escalonamento

		public volatile PCB aguardandoIN = null;       // processo que aguarda entrada do usuário
		public final BlockingQueue<Integer> entradas = new LinkedBlockingQueue<>();

		public SO(HW _hw, int tamMem, int tamPg) {
			hw = _hw;
			ih = new InterruptHandling(hw); // rotinas de tratamento de int
			sc = new SysCallHandling(hw); // chamadas de sistema
			hw.cpu.setAddressOfHandlers(ih, sc);
			utils = new Utilities(hw);
			gm = new GM(tamMem, tamPg);
			gp = new GP();
		}

		// Rot Trat TIMER: salva estado do processo rodando, coloca na fila de prontos, libera escalonador
		public void rotTimer() {
			PCB p = running;
			hw.cpu.saveContext(p);
			p.estado = "pronto";
			ready.addLast(p);
			running = null;
			hw.cpu.ativa = false;
			if (log) System.out.println("  [timer] processo " + p.id + " (" + p.nome + ") sai da CPU, pc=" + p.pc);
			semaSch.release();
		}

		// Rot Tratamento (STOP, overflow, acesso indevido, rm): finaliza processo, libera escalonador
		public void finalizaRunning() {
			PCB p = running;
			hw.cpu.saveContext(p);
			running = null;
			hw.cpu.ativa = false;
			System.out.println("  [fim] processo " + p.id + " (" + p.nome + ") finalizado, ocupava frames "
					+ Arrays.toString(p.tabelaPaginas) + " (resultado visivel com dumpM)");
			gp.libera(p);
			semaSch.release();
		}

		// escolhe o primeiro processo da fila de prontos que pode executar
		private PCB escolhe() {
			for (PCB p : ready) {
				if (modoContinuo || p.liberado) {
					return p;
				}
			}
			return null;
		}

		// Thread Escalonador
		public void loopEscalonador() {
			while (true) {
				semaSch.acquireUninterruptibly();              // aguarda bloqueado (semaSch.wait)
				lock.lock();
				try {
					if (running == null) {
						PCB p = escolhe();
						if (p != null) {
							ready.remove(p);
							running = p;
							p.estado = "rodando";
							hw.cpu.setContext(p.pc, p.reg, p.tabelaPaginas);  // restaura estado na CPU
							if (log) System.out.println("  [escalonador] processo " + p.id + " (" + p.nome + ") -> CPU, pc=" + p.pc);
							semaCPU.release();                 // semaCPU.notify
						}
					}
				} finally {
					lock.unlock();
				}
			}
		}

		// chamada pela thread CPU quando um processo executa SYSCALL IN
		public int leEntrada() {
			aguardandoIN = running;
			System.out.println("IN [processo " + (running == null ? "?" : running.id) + "]: digite um inteiro");
			try {
				return entradas.take();
			} catch (InterruptedException e) {
				return 0;
			} finally {
				aguardandoIN = null;
			}
		}
	}
	// -------------------------------------------------------------------------------------------------------
	// ------------------- S I S T E M A
	// --------------------------------------------------------------------

	public HW hw;
	public SO so;
	public Programs progs;

	public Sistema(int tamMem, int tamPg, int delta) {
		hw = new HW(tamMem, tamPg, delta);    // memoria do HW tem tamMem palavras
		so = new SO(hw, tamMem, tamPg);
		hw.cpu.setUtilities(so.utils); // permite cpu fazer dump de memoria ao avancar
		progs = new Programs();
	}

	// aguarda (no shell) até os processos ids terminarem, ou até algum pedir entrada
	private void aguarda(Set<Integer> ids) {
		so.lock.lock();
		try {
			while (true) {
				boolean vivo = false;
				for (int id : ids) {
					if (so.gp.busca(id) != null) vivo = true;
				}
				if (!vivo || so.aguardandoIN != null) return;
				so.fimProcesso.await(50, TimeUnit.MILLISECONDS);
			}
		} catch (InterruptedException e) {
		} finally {
			so.lock.unlock();
		}
	}

	private void ajuda() {
		System.out.println("Comandos:");
		System.out.println("  new <programa>     cria processo (programas: " + progs.nomes() + ")");
		System.out.println("  rm <id>            remove processo");
		System.out.println("  ps                 lista processos");
		System.out.println("  dump <id>          PCB e memoria do processo");
		System.out.println("  dumpM <ini> <fim>  memoria fisica entre ini e fim");
		System.out.println("  frames             mapa de frames do gerente de memoria");
		System.out.println("  exec <id>          executa o processo id (escalonado)");
		System.out.println("  execAll            executa todos os processos de forma escalonada");
		System.out.println("  autoOn / autoOff   liga/desliga escalonamento continuo");
		System.out.println("  traceOn / traceOff liga/desliga trace de instrucoes");
		System.out.println("  logOn / logOff     liga/desliga log do escalonamento");
		System.out.println("  delay <ms>         atraso por instrucao (0 = sem atraso)");
		System.out.println("  exit               sai do sistema");
	}

	// Thread Shell: le comandos do usuario e submete ao SO
	public void run() {
		Thread tEsc = new Thread(so::loopEscalonador, "Escalonador");
		Thread tCPU = new Thread(hw.cpu::loopCPU, "CPU");
		tEsc.setDaemon(true);
		tCPU.setDaemon(true);
		tEsc.start();
		tCPU.start();

		System.out.println("SO iniciado: tamMem=" + hw.mem.pos.length + "  tamPg=" + so.gm.tamPg
				+ "  frames=" + so.gm.nroFrames + "  delta=" + hw.cpu.delta);
		ajuda();

		Scanner in = new Scanner(System.in);
		while (true) {
			System.out.print("> ");
			if (!in.hasNextLine()) break;
			String linha = in.nextLine().trim();
			if (linha.isEmpty()) continue;

			// resposta a um pedido de IN de processo
			if (so.aguardandoIN != null && linha.matches("-?\\d+")) {
				so.entradas.offer(Integer.parseInt(linha));
				continue;
			}

			String[] t = linha.split("[\\s,<>]+");
			if (t.length > 0 && t[0].isEmpty()) t = Arrays.copyOfRange(t, 1, t.length);
			String cmd = t[0];
			try {
				switch (cmd) {
					case "new": {
						int id;
						so.lock.lock();
						try { id = so.gp.criaProcesso(t[1]); } finally { so.lock.unlock(); }
						if (id > 0) System.out.println("processo criado: id " + id);
						break;
					}
					case "rm": {
						boolean ok;
						so.lock.lock();
						try { ok = so.gp.desalocaProcesso(Integer.parseInt(t[1])); } finally { so.lock.unlock(); }
						System.out.println(ok ? "processo removido" : "processo inexistente");
						break;
					}
					case "ps": {
						so.lock.lock();
						try {
							if (so.running == null && so.ready.isEmpty()) System.out.println("nenhum processo");
							if (so.running != null) {
								PCB p = so.running;
								System.out.println("  id " + p.id + "  " + p.nome + "  rodando  frames " + Arrays.toString(p.tabelaPaginas));
							}
							for (PCB p : so.ready) {
								System.out.println("  id " + p.id + "  " + p.nome + "  pronto   pc=" + p.pc + "  frames " + Arrays.toString(p.tabelaPaginas));
							}
						} finally { so.lock.unlock(); }
						break;
					}
					case "dump": {
						so.lock.lock();
						try {
							PCB p = so.gp.busca(Integer.parseInt(t[1]));
							if (p == null) {
								System.out.println("processo inexistente");
							} else {
								if (p == so.running) hw.cpu.saveContext(p);  // mostra contexto atual
								so.utils.dumpProcesso(p);
							}
						} finally { so.lock.unlock(); }
						break;
					}
					case "dumpM": {
						so.lock.lock();
						try { so.utils.dump(Integer.parseInt(t[1]), Integer.parseInt(t[2])); } finally { so.lock.unlock(); }
						break;
					}
					case "frames": {
						so.lock.lock();
						try {
							StringBuilder sb = new StringBuilder();
							for (int f = 0; f < so.gm.nroFrames; f++) {
								sb.append(so.gm.livre[f] ? '.' : '#');
								if ((f + 1) % 64 == 0) sb.append('\n');
							}
							System.out.println(sb.toString().trim());
							System.out.println("frames livres: " + so.gm.framesLivres() + " de " + so.gm.nroFrames
									+ "   (# ocupado, . livre)");
						} finally { so.lock.unlock(); }
						break;
					}
					case "exec": {
						int id = Integer.parseInt(t[1]);
						boolean existe;
						so.lock.lock();
						try {
							PCB p = so.gp.busca(id);
							existe = p != null;
							if (existe) { p.liberado = true; so.semaSch.release(); }
						} finally { so.lock.unlock(); }
						if (!existe) { System.out.println("erro: processo inexistente"); break; }
						aguarda(Collections.singleton(id));
						break;
					}
					case "execAll": {
						Set<Integer> ids = new HashSet<>();
						so.lock.lock();
						try {
							for (PCB p : so.ready) { p.liberado = true; ids.add(p.id); }
							if (so.running != null) ids.add(so.running.id);
							so.semaSch.release();
						} finally { so.lock.unlock(); }
						if (ids.isEmpty()) { System.out.println("nenhum processo"); break; }
						aguarda(ids);
						break;
					}
					case "autoOn":
						so.modoContinuo = true;
						so.semaSch.release();
						System.out.println("escalonamento continuo ligado");
						break;
					case "autoOff":
						so.modoContinuo = false;
						System.out.println("escalonamento continuo desligado");
						break;
					case "traceOn":  hw.cpu.debug = true;  break;
					case "traceOff": hw.cpu.debug = false; break;
					case "logOn":    so.log = true;        break;
					case "logOff":   so.log = false;       break;
					case "delay":    hw.cpu.atraso = Integer.parseInt(t[1]); break;
					case "help":     ajuda();              break;
					case "exit":     System.exit(0);
					default:
						System.out.println("comando desconhecido (help para ajuda)");
				}
			} catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
				System.out.println("parametros invalidos (help para ajuda)");
			}
		}
		System.exit(0);
	}
	// ------------------- S I S T E M A - fim
	// --------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------

	// -------------------------------------------------------------------------------------------------------
	// ------------------- instancia e testa sistema
	public static void main(String args[]) {
		int tamMem = args.length > 0 ? Integer.parseInt(args[0]) : 1024;
		int tamPg  = args.length > 1 ? Integer.parseInt(args[1]) : 8;
		int delta  = args.length > 2 ? Integer.parseInt(args[2]) : 10;
		if (tamPg <= 0 || tamMem <= 0 || tamMem % tamPg != 0 || delta <= 0) {
			System.out.println("tamMem deve ser multiplo de tamPg, e todos positivos");
			return;
		}
		Sistema s = new Sistema(tamMem, tamPg, delta);
		s.run();
	}

	// -------------------------------------------------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------
	// --------------- P R O G R A M A S - não fazem parte do sistema
	// esta classe representa programas armazenados (como se estivessem em disco)
	// que podem ser carregados para a memória (load faz isto)

	public class Program {
		public String name;
		public Word[] image;

		public Program(String n, Word[] i) {
			name = n;
			image = i;
		}
	}

	public class Programs {

		public Word[] retrieveProgram(String pname) {
			for (Program p : progs) {
				if (p != null && p.name.equals(pname))   // equals: nome vem digitado pelo usuário
					return p.image;
			}
			return null;
		}

		public String nomes() {
			StringJoiner sj = new StringJoiner(", ");
			for (Program p : progs) sj.add(p.name);
			return sj.toString();
		}

		public Program[] progs = {
				new Program("fatorial",
						new Word[] {
								// este fatorial so aceita valores positivos. nao pode ser zero
								// linha coment
								new Word(Opcode.LDI, 0, -1, 7), // 0 r0 é valor a calcular fatorial
								new Word(Opcode.LDI, 1, -1, 1), // 1 r1 é 1 para multiplicar (por r0)
								new Word(Opcode.LDI, 6, -1, 1), // 2 r6 é 1 o decremento
								new Word(Opcode.LDI, 7, -1, 8), // 3 r7 tem posicao 8 para fim do programa
								new Word(Opcode.JMPIE, 7, 0, 0), // 4 se r0=0 pula para r7(=8)
								new Word(Opcode.MULT, 1, 0, -1), // 5 r1 = r1 * r0 (r1 acumula o produto por cada termo)
								new Word(Opcode.SUB, 0, 6, -1), // 6 r0 = r0 - r6 (r6=1) decrementa r0 para proximo
																// termo
								new Word(Opcode.JMP, -1, -1, 4), // 7 vai p posicao 4
								new Word(Opcode.STD, 1, -1, 10), // 8 coloca valor de r1 na posição 10
								new Word(Opcode.STOP, -1, -1, -1), // 9 stop
								new Word(Opcode.DATA, -1, -1, -1) // 10 ao final o valor está na posição 10 da memória
						}),

				new Program("fatorialV2",
						new Word[] {
								new Word(Opcode.LDI, 0, -1, 5), // numero para colocar na memoria, ou pode ser lido
								new Word(Opcode.STD, 0, -1, 19),
								new Word(Opcode.LDD, 0, -1, 19),
								new Word(Opcode.LDI, 1, -1, -1),
								new Word(Opcode.LDI, 2, -1, 13), // SALVAR POS STOP
								new Word(Opcode.JMPIL, 2, 0, -1), // caso negativo pula pro STD
								new Word(Opcode.LDI, 1, -1, 1),
								new Word(Opcode.LDI, 6, -1, 1),
								new Word(Opcode.LDI, 7, -1, 13),
								new Word(Opcode.JMPIE, 7, 0, 0), // POS 9 pula para STD (Stop-1)
								new Word(Opcode.MULT, 1, 0, -1),
								new Word(Opcode.SUB, 0, 6, -1),
								new Word(Opcode.JMP, -1, -1, 9), // pula para o JMPIE
								new Word(Opcode.STD, 1, -1, 18),
								new Word(Opcode.LDI, 8, -1, 2), // escrita
								new Word(Opcode.LDI, 9, -1, 18), // endereco com valor a escrever
								new Word(Opcode.SYSCALL, -1, -1, -1),
								new Word(Opcode.STOP, -1, -1, -1), // POS 17
								new Word(Opcode.DATA, -1, -1, -1), // POS 18
								new Word(Opcode.DATA, -1, -1, -1) } // POS 19
				),

				new Program("progMinimo",
						new Word[] {
								new Word(Opcode.LDI, 0, -1, 999),
								new Word(Opcode.STD, 0, -1, 8),
								new Word(Opcode.STD, 0, -1, 9),
								new Word(Opcode.STD, 0, -1, 10),
								new Word(Opcode.STD, 0, -1, 11),
								new Word(Opcode.STD, 0, -1, 12),
								new Word(Opcode.STOP, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1), // 7
								new Word(Opcode.DATA, -1, -1, -1), // 8
								new Word(Opcode.DATA, -1, -1, -1), // 9
								new Word(Opcode.DATA, -1, -1, -1), // 10
								new Word(Opcode.DATA, -1, -1, -1), // 11
								new Word(Opcode.DATA, -1, -1, -1), // 12
								new Word(Opcode.DATA, -1, -1, -1) // 13
						}),

				new Program("fibonacci10",
						new Word[] { // mesmo que prog exemplo, so que usa r0 no lugar de r8
								new Word(Opcode.LDI, 1, -1, 0),
								new Word(Opcode.STD, 1, -1, 20),
								new Word(Opcode.LDI, 2, -1, 1),
								new Word(Opcode.STD, 2, -1, 21),
								new Word(Opcode.LDI, 0, -1, 22),
								new Word(Opcode.LDI, 6, -1, 6),
								new Word(Opcode.LDI, 7, -1, 31),
								new Word(Opcode.LDI, 3, -1, 0),
								new Word(Opcode.ADD, 3, 1, -1),
								new Word(Opcode.LDI, 1, -1, 0),
								new Word(Opcode.ADD, 1, 2, -1),
								new Word(Opcode.ADD, 2, 3, -1),
								new Word(Opcode.STX, 0, 2, -1),
								new Word(Opcode.ADDI, 0, -1, 1),
								new Word(Opcode.SUB, 7, 0, -1),
								new Word(Opcode.JMPIG, 6, 7, -1),
								new Word(Opcode.STOP, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1), // POS 20
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1) // ate aqui - serie de fibonacci ficara armazenada
						}),

				new Program("fibonacci10v2",
						new Word[] { // mesmo que prog exemplo, so que usa r0 no lugar de r8
								new Word(Opcode.LDI, 1, -1, 0),
								new Word(Opcode.STD, 1, -1, 20),
								new Word(Opcode.LDI, 2, -1, 1),
								new Word(Opcode.STD, 2, -1, 21),
								new Word(Opcode.LDI, 0, -1, 22),
								new Word(Opcode.LDI, 6, -1, 6),
								new Word(Opcode.LDI, 7, -1, 31),
								new Word(Opcode.MOVE, 3, 1, -1),
								new Word(Opcode.MOVE, 1, 2, -1),
								new Word(Opcode.ADD, 2, 3, -1),
								new Word(Opcode.STX, 0, 2, -1),
								new Word(Opcode.ADDI, 0, -1, 1),
								new Word(Opcode.SUB, 7, 0, -1),
								new Word(Opcode.JMPIG, 6, 7, -1),
								new Word(Opcode.STOP, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1), // POS 20
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1) // ate aqui - serie de fibonacci ficara armazenada
						}),
				new Program("fibonacciREAD",
						new Word[] {
								// mesmo que prog exemplo, so que usa r0 no lugar de r8
								new Word(Opcode.LDI, 8, -1, 1), // leitura
								new Word(Opcode.LDI, 9, -1, 55), // endereco a guardar o tamanho da serie de fib a gerar
																	// - pode ser de 1 a 20
								new Word(Opcode.SYSCALL, -1, -1, -1),
								new Word(Opcode.LDD, 7, -1, 55),
								new Word(Opcode.LDI, 3, -1, 0),
								new Word(Opcode.ADD, 3, 7, -1),
								new Word(Opcode.LDI, 4, -1, 36), // posicao para qual ira pular (stop) *
								new Word(Opcode.LDI, 1, -1, -1), // caso negativo
								new Word(Opcode.STD, 1, -1, 41),
								new Word(Opcode.JMPIL, 4, 7, -1), // pula pra stop caso negativo *
								new Word(Opcode.JMPIE, 4, 7, -1), // pula pra stop caso 0
								new Word(Opcode.ADDI, 7, -1, 41), // fibonacci + posição do stop
								new Word(Opcode.LDI, 1, -1, 0),
								new Word(Opcode.STD, 1, -1, 41), // 25 posicao de memoria onde inicia a serie de
																	// fibonacci gerada
								new Word(Opcode.SUBI, 3, -1, 1), // se 1 pula pro stop
								new Word(Opcode.JMPIE, 4, 3, -1),
								new Word(Opcode.ADDI, 3, -1, 1),
								new Word(Opcode.LDI, 2, -1, 1),
								new Word(Opcode.STD, 2, -1, 42),
								new Word(Opcode.SUBI, 3, -1, 2), // se 2 pula pro stop
								new Word(Opcode.JMPIE, 4, 3, -1),
								new Word(Opcode.LDI, 0, -1, 43),
								new Word(Opcode.LDI, 6, -1, 25), // salva posição de retorno do loop
								new Word(Opcode.LDI, 5, -1, 0), // salva tamanho
								new Word(Opcode.ADD, 5, 7, -1),
								new Word(Opcode.LDI, 7, -1, 0), // zera (inicio do loop)
								new Word(Opcode.ADD, 7, 5, -1), // recarrega tamanho
								new Word(Opcode.LDI, 3, -1, 0),
								new Word(Opcode.ADD, 3, 1, -1),
								new Word(Opcode.LDI, 1, -1, 0),
								new Word(Opcode.ADD, 1, 2, -1),
								new Word(Opcode.ADD, 2, 3, -1),
								new Word(Opcode.STX, 0, 2, -1),
								new Word(Opcode.ADDI, 0, -1, 1),
								new Word(Opcode.SUB, 7, 0, -1),
								new Word(Opcode.JMPIG, 6, 7, -1), // volta para o inicio do loop
								new Word(Opcode.STOP, -1, -1, -1), // POS 36
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1), // POS 41
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1)
						}),
				new Program("PB",
						new Word[] {
								// dado um inteiro em alguma posição de memória,
								// se for negativo armazena -1 na saída; se for positivo responde o fatorial do
								// número na saída
								new Word(Opcode.LDI, 0, -1, 7), // numero para colocar na memoria
								new Word(Opcode.STD, 0, -1, 50),
								new Word(Opcode.LDD, 0, -1, 50),
								new Word(Opcode.LDI, 1, -1, -1),
								new Word(Opcode.LDI, 2, -1, 13), // SALVAR POS STOP
								new Word(Opcode.JMPIL, 2, 0, -1), // caso negativo pula pro STD
								new Word(Opcode.LDI, 1, -1, 1),
								new Word(Opcode.LDI, 6, -1, 1),
								new Word(Opcode.LDI, 7, -1, 13),
								new Word(Opcode.JMPIE, 7, 0, 0), // POS 9 pula pra STD (Stop-1)
								new Word(Opcode.MULT, 1, 0, -1),
								new Word(Opcode.SUB, 0, 6, -1),
								new Word(Opcode.JMP, -1, -1, 9), // pula para o JMPIE
								new Word(Opcode.STD, 1, -1, 15),
								new Word(Opcode.STOP, -1, -1, -1), // POS 14
								new Word(Opcode.DATA, -1, -1, -1) // POS 15
						}),
				new Program("PC",
						new Word[] {
								// Para um N definido (10 por exemplo)
								// o programa ordena um vetor de N números em alguma posição de memória;
								// ordena usando bubble sort
								// loop ate que não swap nada
								// passando pelos N valores
								// faz swap de vizinhos se da esquerda maior que da direita
								new Word(Opcode.LDI, 7, -1, 5), // TAMANHO DO BUBBLE SORT (N)
								new Word(Opcode.LDI, 6, -1, 5), // aux N
								new Word(Opcode.LDI, 5, -1, 46), // LOCAL DA MEMORIA
								new Word(Opcode.LDI, 4, -1, 47), // aux local memoria
								new Word(Opcode.LDI, 0, -1, 4), // colocando valores na memoria
								new Word(Opcode.STD, 0, -1, 46),
								new Word(Opcode.LDI, 0, -1, 3),
								new Word(Opcode.STD, 0, -1, 47),
								new Word(Opcode.LDI, 0, -1, 5),
								new Word(Opcode.STD, 0, -1, 48),
								new Word(Opcode.LDI, 0, -1, 1),
								new Word(Opcode.STD, 0, -1, 49),
								new Word(Opcode.LDI, 0, -1, 2),
								new Word(Opcode.STD, 0, -1, 50), // colocando valores na memoria até aqui - POS 13
								new Word(Opcode.LDI, 3, -1, 25), // Posicao para pulo CHAVE 1
								new Word(Opcode.STD, 3, -1, 99),
								new Word(Opcode.LDI, 3, -1, 22), // Posicao para pulo CHAVE 2
								new Word(Opcode.STD, 3, -1, 98),
								new Word(Opcode.LDI, 3, -1, 38), // Posicao para pulo CHAVE 3
								new Word(Opcode.STD, 3, -1, 97),
								new Word(Opcode.LDI, 3, -1, 25), // Posicao para pulo CHAVE 4 (não usada)
								new Word(Opcode.STD, 3, -1, 96),
								new Word(Opcode.LDI, 6, -1, 0), // r6 = r7 - 1 POS 22
								new Word(Opcode.ADD, 6, 7, -1),
								new Word(Opcode.SUBI, 6, -1, 1), // ate aqui
								new Word(Opcode.JMPIEM, -1, 6, 97), // CHAVE 3 para pular quando r7 for 1 e r6 0 para
																	// interomper o loop de vez do programa
								new Word(Opcode.LDX, 0, 5, -1), // r0 e ra pegando valores das posições da memoria POS
																// 26
								new Word(Opcode.LDX, 1, 4, -1),
								new Word(Opcode.LDI, 2, -1, 0),
								new Word(Opcode.ADD, 2, 0, -1),
								new Word(Opcode.SUB, 2, 1, -1),
								new Word(Opcode.ADDI, 4, -1, 1),
								new Word(Opcode.SUBI, 6, -1, 1),
								new Word(Opcode.JMPILM, -1, 2, 99), // LOOP chave 1 caso neg procura prox
								new Word(Opcode.STX, 5, 1, -1),
								new Word(Opcode.SUBI, 4, -1, 1),
								new Word(Opcode.STX, 4, 0, -1),
								new Word(Opcode.ADDI, 4, -1, 1),
								new Word(Opcode.JMPIGM, -1, 6, 99), // LOOP chave 1 POS 38
								new Word(Opcode.ADDI, 5, -1, 1),
								new Word(Opcode.SUBI, 7, -1, 1),
								new Word(Opcode.LDI, 4, -1, 0), // r4 = r5 + 1 POS 41
								new Word(Opcode.ADD, 4, 5, -1),
								new Word(Opcode.ADDI, 4, -1, 1), // ate aqui
								new Word(Opcode.JMPIGM, -1, 7, 98), // LOOP chave 2
								new Word(Opcode.STOP, -1, -1, -1), // POS 45
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1)
						})
		};
	}
}
