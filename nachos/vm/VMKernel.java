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
	public class InvertedPage {
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
	}

	/**
	 * Initialize this kernel.
	 */
	public void initialize(String[] args) {
		super.initialize(args);

		swappingFile = ThreadedKernel.fileSystem.open("SwappingFile", true);

		freeSwapAreas = new LinkedList<>();

		swapAreaCounter = 0;

		int numPhysPages = Machine.processor().getNumPhysPages();

		invertedPageTable = new InvertedPage[numPhysPages];

		for (int i = 0; i < numPhysPages; i++) {
			invertedPageTable[i] = new InvertedPage(false, -1, null);
		}

		pageReplacementIndex = 0;

		lock = new Lock();

		condition = new Condition(lock);

		numPinnedPages = 0;
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
		System.out.println(VMKernel.freeSwapAreas);
		swappingFile.close();
        ThreadedKernel.fileSystem.remove("SwappingFile");
		super.terminate();
	}

	// dummy variables to make javac smarter
	private static VMProcess dummy1 = null;

	private static final char dbgVM = 'v';

	public static LinkedList<Integer> freeSwapAreas;

	public static int swapAreaCounter;

	public static InvertedPage[] invertedPageTable;

	public static OpenFile swappingFile;

	public static Lock lock;

	public static Condition condition;

	public static int pageReplacementIndex;

	public static int numPinnedPages;
}
