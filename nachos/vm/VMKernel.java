package nachos.vm;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;

import java.util.*;

/**
 * A kernel that can support multiple demand-paging user processes.
 */
public class VMKernel extends UserKernel {
	private class InvertedPage {
        public boolean pin;
		public int vpn;
		public UserProcess process;


		public InvertedPage(boolean pin, int vpn, UserProcess process) {
			this.pin = pin;
			this.vpn = vpn;
			this.process = process;
		}
	}

	/**
	 * Allocate a new VM kernel.
	 */
	public VMKernel() {
		super();
		
		freeSwapAreas = new LinkedList<>();

		swappingFile = ThreadedKernel.fileSystem.open("SwappingFile", true);

		int numPhysPages = Machine.processor().getNumPhysPages();

		invertedPageTable = new InvertedPage[numPhysPages];

		for (int i = 0; i < numPhysPages; i++) {
			freeSwapAreas.add(i);
			invertedPageTable[i] = new InvertedPage(false, -1, null);
		}

		lock = new Lock();

		condition = new Condition(lock);
	}

	/**
	 * Initialize this kernel.
	 */
	public void initialize(String[] args) {
		super.initialize(args);
	}

	/**
	 * Test this kernel.
	 */
	public void selfTest() {
		super.selfTest();
	}

	/**
	 * Start running user programs.
	 */
	public void run() {
		super.run();
	}

	/**
	 * Terminate this kernel. Never returns.
	 */
	public void terminate() {
        ThreadedKernel.fileSystem.remove("SwappingFile");
		super.terminate();
	}

	// dummy variables to make javac smarter
	private static VMProcess dummy1 = null;

	private static final char dbgVM = 'v';

	public static LinkedList<Integer> freeSwapAreas;

	public static InvertedPage[] invertedPageTable;

	public static OpenFile swappingFile;

	public static Lock lock;

	public static Condition condition;
}
