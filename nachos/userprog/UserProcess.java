package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;

import java.io.EOFException;

import java.util.*;

/**
 * Encapsulates the state of a user process that is not contained in its user
 * thread (or threads). This includes its address translation state, a file
 * table, and information about the program being executed.
 * 
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 * 
 * @see nachos.vm.VMProcess
 * @see nachos.network.NetProcess
 */
public class UserProcess {
	/**
	 * Allocate a new process.
	 */
	public UserProcess() {
		this.fdTable = new OpenFile[16];
		this.fdTable[0] = UserKernel.console.openForReading();
		this.fdTable[1] = UserKernel.console.openForWriting();
		this.childMap = new HashMap<>();
		this.childMapLock = new Lock();

		this.processNumber = UserKernel.nextPID();

		UserKernel.incrementProcessCount();
	}

	/**
	 * Allocate and return a new process of the correct class. The class name is
	 * specified by the <tt>nachos.conf</tt> key
	 * <tt>Kernel.processClassName</tt>.
	 * 
	 * @return a new process of the correct class.
	 */
	public static UserProcess newUserProcess() {
	        String name = Machine.getProcessClassName ();

		// If Lib.constructObject is used, it quickly runs out
		// of file descriptors and throws an exception in
		// createClassLoader.  Hack around it by hard-coding
		// creating new processes of the appropriate type.

		if (name.equals ("nachos.userprog.UserProcess")) {
		    return new UserProcess ();
		} else if (name.equals ("nachos.vm.VMProcess")) {
		    return new VMProcess ();
		} else {
		    return (UserProcess) Lib.constructObject(Machine.getProcessClassName());
		}
	}

	/**
	 * Execute the specified program with the specified arguments. Attempts to
	 * load the program, and then forks a thread to run it.
	 * 
	 * @param name the name of the file containing the executable.
	 * @param args the arguments to pass to the executable.
	 * @return <tt>true</tt> if the program was successfully executed.
	 */
	public boolean execute(String name, String[] args) {
		if (!load(name, args))
			return false;

		thread = new UThread(this);
		thread.setName(name).fork();

		return true;
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		Machine.processor().setPageTable(pageTable);
	}

	/**
	 * Read a null-terminated string from this process's virtual memory. Read at
	 * most <tt>maxLength + 1</tt> bytes from the specified address, search for
	 * the null terminator, and convert it to a <tt>java.lang.String</tt>,
	 * without including the null terminator. If no null terminator is found,
	 * returns <tt>null</tt>.
	 * 
	 * @param vaddr the starting virtual address of the null-terminated string.
	 * @param maxLength the maximum number of characters in the string, not
	 * including the null terminator.
	 * @return the string read, or <tt>null</tt> if no null terminator was
	 * found.
	 */
	public String readVirtualMemoryString(int vaddr, int maxLength) {
		Lib.assertTrue(maxLength >= 0);

		byte[] bytes = new byte[maxLength + 1];

		int bytesRead = readVirtualMemory(vaddr, bytes);

		for (int length = 0; length < bytesRead; length++) {
			if (bytes[length] == 0)
				return new String(bytes, 0, length);
		}

		return null;
	}

	/**
	 * Transfer data from this process's virtual memory to all of the specified
	 * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 * 
	 * @param vaddr the first byte of virtual memory to read.
	 * @param data the array where the data will be stored.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data) {
		return readVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from this process's virtual memory to the specified array.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 * 
	 * @param vaddr the first byte of virtual memory to read.
	 * @param data the array where the data will be stored.
	 * @param offset the first byte to write in the array.
	 * @param length the number of bytes to transfer from virtual memory to the
	 * array.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0
				&& offset + length <= data.length);

		// edge case for read
		if (vaddr + length >= numPages * pageSize) {
			return 0;
		}

		return transfer(vaddr, data, offset, length, true);
	}

	/**
	 * Transfer all data from the specified array to this process's virtual
	 * memory. Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 * 
	 * @param vaddr the first byte of virtual memory to write.
	 * @param data the array containing the data to transfer.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data) {
		return writeVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from the specified array to this process's virtual memory.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 * 
	 * @param vaddr the first byte of virtual memory to write.
	 * @param data the array containing the data to transfer.
	 * @param offset the first byte to transfer from the array.
	 * @param length the number of bytes to transfer from the array to virtual
	 * memory.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0
				&& offset + length <= data.length);

		return transfer(vaddr, data, offset, length, false);
	}

	private int transfer(int vaddr, byte[] data, int offset, int length, boolean read) {
		byte[] memory = Machine.processor().getMemory();

		int totalBytesTransferred = 0, virtualAddressEnd = this.numPages * pageSize;

		// not within bounds of process' virtual address space
		if (vaddr < 0 || vaddr >= virtualAddressEnd)
			return totalBytesTransferred;

		int currentVirtualPage = Machine.processor().pageFromAddress(vaddr);
		int endVirtualPage = Machine.processor().pageFromAddress(vaddr+length);
		int virtualLengthEnd = vaddr+length;

		// copy everything we can until we exceed last virtual page allocated for this process
		while (currentVirtualPage < pageTable.length && currentVirtualPage <= endVirtualPage) {
			if (!pageTable[currentVirtualPage].valid)
				break; 

			if (!read && pageTable[currentVirtualPage].readOnly)
				break;
			
			int currentPageVAStart = currentVirtualPage * pageSize;
			int currentPageVAEnd = currentPageVAStart + pageSize-1;

			if (vaddr > currentPageVAStart && virtualLengthEnd >= currentPageVAEnd) {
				int addrOffset = Machine.processor().offsetFromAddress(vaddr);
				int physicalAddress = pageSize * pageTable[currentVirtualPage].ppn + addrOffset;
				int dataStart = offset+totalBytesTransferred;
				int difference = pageSize - addrOffset;

				if (read) {
					System.arraycopy(memory, physicalAddress, data, dataStart, difference);
				} else {
					System.arraycopy(data, dataStart, memory, physicalAddress, difference);
				}

				totalBytesTransferred = totalBytesTransferred + difference;
			} else if (vaddr <= currentPageVAStart && virtualLengthEnd >= currentPageVAEnd) {
				int physicalAddress = pageSize * pageTable[currentVirtualPage].ppn;
				int dataStart = offset+totalBytesTransferred;

				if (read) {
					System.arraycopy(memory, physicalAddress, data, dataStart, pageSize);
				} else {
					System.arraycopy(data, dataStart, memory, physicalAddress, pageSize);
				}

				totalBytesTransferred = totalBytesTransferred + pageSize;
			} else if (vaddr <= currentPageVAStart && virtualLengthEnd < currentPageVAEnd) {
				int physicalAddress = pageSize * pageTable[currentVirtualPage].ppn;
				int dataStart = offset+totalBytesTransferred;

				if (read) {
					System.arraycopy(memory, physicalAddress, data, dataStart, virtualLengthEnd - currentPageVAStart);
				} else {
					System.arraycopy(data, dataStart, memory, physicalAddress, virtualLengthEnd - currentPageVAStart);
				}

				totalBytesTransferred = totalBytesTransferred + virtualLengthEnd - currentPageVAStart;
			} else { 
				int addrOffset = Machine.processor().offsetFromAddress(vaddr);
				int physicalAddress = pageSize * pageTable[currentVirtualPage].ppn + addrOffset;
				int dataStart = offset+totalBytesTransferred;

				if (read) {
					System.arraycopy(memory, physicalAddress, data, dataStart, length);
				} else {
					System.arraycopy(data, dataStart, memory, physicalAddress, length);
				}

				totalBytesTransferred = totalBytesTransferred + length;
			}

			pageTable[currentVirtualPage].used = true;

			if (!read) {
				pageTable[currentVirtualPage].dirty = true;
			}

			currentVirtualPage++;
		}		

		return totalBytesTransferred;
	}

	/**
	 * Load the executable with the specified name into this process, and
	 * prepare to pass it the specified arguments. Opens the executable, reads
	 * its header information, and copies sections and arguments into this
	 * process's virtual memory.
	 * 
	 * @param name the name of the file containing the executable.
	 * @param args the arguments to pass to the executable.
	 * @return <tt>true</tt> if the executable was successfully loaded.
	 */
	private boolean load(String name, String[] args) {
		Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");

		OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
		if (executable == null) {
			Lib.debug(dbgProcess, "\topen failed");
			return false;
		}

		try {
			coff = new Coff(executable);
		}
		catch (EOFException e) {
			executable.close();
			Lib.debug(dbgProcess, "\tcoff load failed");
			return false;
		}

		// make sure the sections are contiguous and start at page 0
		numPages = 0;
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);
			if (section.getFirstVPN() != numPages) {
				coff.close();
				Lib.debug(dbgProcess, "\tfragmented executable");
				return false;
			}
			numPages += section.getLength();
		}

		// make sure the argv array will fit in one page
		byte[][] argv = new byte[args.length][];
		int argsSize = 0;
		for (int i = 0; i < args.length; i++) {
			argv[i] = args[i].getBytes();
			// 4 bytes for argv[] pointer; then string plus one for null byte
			argsSize += 4 + argv[i].length + 1;
		}
		if (argsSize > pageSize) {
			coff.close();
			Lib.debug(dbgProcess, "\targuments too long");
			return false;
		}

		// program counter initially points at the program entry point
		initialPC = coff.getEntryPoint();

		// next comes the stack; stack pointer initially points to top of it
		numPages += stackPages;
		initialSP = numPages * pageSize;

		// and finally reserve 1 page for arguments
		numPages++;

		if (!loadSections())
			return false;

		// store arguments in last page
		int entryOffset = (numPages - 1) * pageSize;
		int stringOffset = entryOffset + args.length * 4;

		this.argc = args.length;
		this.argv = entryOffset;

		for (int i = 0; i < argv.length; i++) {
			byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
			Lib.assertTrue(writeVirtualMemory(entryOffset, stringOffsetBytes) == 4);
			entryOffset += 4;
			Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) == argv[i].length);
			stringOffset += argv[i].length;
			Lib.assertTrue(writeVirtualMemory(stringOffset, new byte[] { 0 }) == 1);
			stringOffset += 1;
		}

		return true;
	}

	/**
	 * Allocates memory for this process, and loads the COFF sections into
	 * memory. If this returns successfully, the process will definitely be run
	 * (this is the last step in process initialization that can fail).
	 * 
	 * @return <tt>true</tt> if the sections were successfully loaded.
	 */
	protected boolean loadSections() {
		if (numPages > Machine.processor().getNumPhysPages()) {
			coff.close();
			Lib.debug(dbgProcess, "\tinsufficient physical memory");
			return false;
		}

		this.pageTable = new TranslationEntry[numPages];

		for (int i = 0; i < numPages; i++) {
			int ppn = UserKernel.acquirePPN();

			if(ppn == -1) {
				// release previously acquired physical pages
				for (int j = 0; j < i; j++) {
					UserKernel.releasePPN(pageTable[j].ppn);
				}

				return false;
			}

			pageTable[i] = new TranslationEntry(i, ppn, true, false, false, false);
		}

		// load sections

		int numCoffSections = this.coff.getNumSections();

		boolean isSuccessful = true;

		for (int s = 0; s < numCoffSections; s++) {
			CoffSection section = this.coff.getSection(s);

			Lib.debug(dbgProcess, "\tinitializing " + section.getName()
					+ " section (" + section.getLength() + " pages)");

			for (int i = 0; i < section.getLength(); i++) {
				int vpn = section.getFirstVPN() + i;
				// System.out.println("412:" + vpn);

				if (pageTable[vpn] != null) { 
					pageTable[vpn].readOnly = section.isReadOnly();
					section.loadPage(i, pageTable[vpn].ppn);
				} else {
					isSuccessful = false;
					break;
				}
			}

			if (!isSuccessful)
				break;
		}

		// for(TranslationEntry te : pageTable) {
		// 	System.out.println(te.readOnly);
		// }

		return isSuccessful;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
		for (int i = 0; i < pageTable.length; i++) {
			TranslationEntry entry = pageTable[i];

			if (entry == null) {
				continue;
			}

			UserKernel.releasePPN(entry.ppn);
			pageTable[i] = null;
		}
	}

	/**
	 * Initialize the processor's registers in preparation for running the
	 * program loaded into this process. Set the PC register to point at the
	 * start function, set the stack pointer register to point at the top of the
	 * stack, set the A0 and A1 registers to argc and argv, respectively, and
	 * initialize all other registers to 0.
	 */
	public void initRegisters() {
		Processor processor = Machine.processor();

		// by default, everything's 0
		for (int i = 0; i < processor.numUserRegisters; i++)
			processor.writeRegister(i, 0);

		// initialize PC and SP according
		processor.writeRegister(Processor.regPC, initialPC);
		processor.writeRegister(Processor.regSP, initialSP);

		// initialize the first two argument registers to argc and argv
		processor.writeRegister(Processor.regA0, argc);
		processor.writeRegister(Processor.regA1, argv);
	}

	/**
	 * Handle the halt() system call.
	 */
	private int handleHalt() {
		if(this.processNumber != 0) {
			return -1;
		}

		Machine.halt();

		Lib.assertNotReached("Machine.halt() did not halt machine!");
		return 0;
	}

	/**
	 * Handle the exit() system call.
	 */
	private int handleExit(int status) {
	    // Do not remove this call to the autoGrader...
		Machine.autoGrader().finishingCurrentProcess(status);
		// ...and leave it as the top of handleExit so that we
		// can grade your implementation.

		Lib.debug(dbgProcess, "UserProcess.handleExit (" + status + ")");

		for (int i = 2; i < fdTable.length; i++) {
			if (fdTable[i] != null) {
				handleClose(i);
			}
		}

		for (UserProcess child : childMap.values()) {
			child.parentProcess = null;
		}

		unloadSections();

		coff.close();

		this.status = status;

		UserKernel.decrementProcessCount();

		if (UserKernel.processCount == 0) {
			Kernel.kernel.terminate();
		}

		// finish the current thread
		// will wake up parent thread in implementation in KThread
		UThread.currentThread().finish();

		return 0;
	}

	private int handleExec(int virtualAddress, int argc, int argv) {
		// argc must be non-negative
		if (virtualAddress >= 0 && virtualAddress < pageSize * numPages && argv >= 0 && argv < pageSize * numPages && argc >= 0) {
			String name = readVirtualMemoryString(virtualAddress, 256);

			// this string must include the ".coff" extension
			if (name != null && name.length() > 0 && name.endsWith(".coff")) {
				// get the arguments address
				// each pointer has 4 type, use readvirtualmemory() and track the current vaddr

				String[] arguments = new String[argc];

				int i = 0;

				while (i < argc) {
					byte[] buffer = new byte[4];

					// read in address (argv contains pointers)
					int readCount = readVirtualMemory(argv + i * 4, buffer);

					// read in 4 bytes (if not there was an issue)
					if (readCount == 4) {
						arguments[i++] = readVirtualMemoryString(Lib.bytesToInt(buffer, 0), 256);

						if (arguments[i-1] == null) {
							return -1;
						}
					} else {
						return -1;
					}
				}

				System.out.println("inside handleExec of parent");

				// create child process
				UserProcess child = new UserProcess();

				if (child.execute(name, arguments)) {
					child.parentProcess = this;

					this.childMapLock.acquire();
					this.childMap.put(child.processNumber, child);
					System.out.println("line 570: " + this.childMap.size());
					this.childMapLock.release();

					return child.processNumber;
				}
			}
		}

		return -1;
	}

	private int handleJoin(int pid, int status) {
		System.out.println("in handleJoin " + pid);
		 if (pid >= 0 && status >= 0 && status < pageSize * numPages) {
			this.childMapLock.acquire();
			UserProcess child = childMap.getOrDefault(pid, null);
			this.childMapLock.release();

			System.out.println("inside join, size: "+ childMap.size());

			if (child != null) {
				// wait for child thread to finish
				child.thread.join();

				// remove from map
				this.childMapLock.acquire();
				this.childMap.remove(pid);
				this.childMapLock.release();

				// child exited normally
				if(child.status != null) {
					byte[] statusBytes;
					statusBytes = Lib.bytesFromInt(child.status);

					if (writeVirtualMemory(status, statusBytes) == 4) {
						return 1;
					} else {
						return 0;
					}
                } else { // child did not exit normally
					return 0;
				}
			}
		}

		return -1;
	}

	private int handleCreate(int virtualAddress) {
		String name = readVirtualMemoryString(virtualAddress, 256);

		if(name != null) {
			OpenFile file = UserKernel.fileSystem.open(name, true);

			if (file != null) {
				int i = 2;

				while(i < 16) {
					if (fdTable[i] == null) {
						fdTable[i] = file;
						return i;
					}

					i++;
				}
			}
		}

		return -1;	
	}

	private int handleOpen(int virtualAddress) {
		String name = readVirtualMemoryString(virtualAddress, 256);

		if(name != null && name.length() <= 256) {
			OpenFile file = UserKernel.fileSystem.open(name, false);

			if (file != null) {
				int i = 2;

				while(i < 16) {
					if (fdTable[i] == null) {
						fdTable[i] = file;
						return i;
					}

					i++;
				}
			}
		}

		return -1;
	}

	// read bytes from file corresponding to fileDescriptor into buffer
	private int handleRead(int fileDescriptor, int buffer, int size) {
		int virtualAddressEnd = this.numPages * pageSize;

		if (fileDescriptor >= 0 && fileDescriptor < fdTable.length && fdTable[fileDescriptor] != null && size >= 0 
			&& buffer >= 0 && buffer < virtualAddressEnd) { 
			int firstVirtualPage = Processor.pageFromAddress(buffer);
			int lastVirtualPage = Processor.pageFromAddress(buffer + size);

			// System.out.println(firstVirtualPage+","+lastVirtualPage+","+pageTable.length);

			while (firstVirtualPage <= lastVirtualPage) {
				if (!pageTable[firstVirtualPage].valid || pageTable[firstVirtualPage].readOnly) {
					return -1;
				}

				firstVirtualPage++;
			}

			byte[] local = new byte[size];

			int result = fdTable[fileDescriptor].read(local, 0, size);

			if (result != -1 && result <= size) { 
				// write it to virtual mem
				// check when return is 0
				int res = writeVirtualMemory(buffer, local, 0, result);
				if(res == 0) {
					if(size == 0) {
						return 0;
					} else {
						return -1;
					}
				}
				return result;
			}
		}

		return -1;	
	}

	// read amount of size bytes from buffer to fileDescriptor
	private int handleWrite(int fileDescriptor, int buffer, int size) {
		int virtualAddressEnd = this.numPages * pageSize;

		if (fileDescriptor >= 0 && fileDescriptor < fdTable.length && fdTable[fileDescriptor] != null && size >= 0 
			&& buffer >= 0 && buffer < virtualAddressEnd && buffer+size < virtualAddressEnd) { 
			int firstVirtualPage = Processor.pageFromAddress(buffer);
			int lastVirtualPage = Processor.pageFromAddress(buffer + size); 

			while (firstVirtualPage <= lastVirtualPage) {
				if (!pageTable[firstVirtualPage].valid) {
					return -1;
				}

				firstVirtualPage++;
			}

			byte[] local = new byte[size];

			if (readVirtualMemory(buffer, local) == size) { 
				int result = fdTable[fileDescriptor].write(local, 0, size);

				// error if written data not same as size!
				if (result == size) {
					return result;
				}
			}
		}

		return -1;
	}

	private int handleClose(int fileDescriptor) {
		if (fileDescriptor >= 0 && fileDescriptor < fdTable.length && fdTable[fileDescriptor] != null) {
			OpenFile fileToClose = fdTable[fileDescriptor];
			fileToClose.close();

			fdTable[fileDescriptor] = null;

			return 0;	
		}

		return -1;
	}

	private int handleUnlink(int virtualAddress) {
		String name = readVirtualMemoryString(virtualAddress, 256);

		return (name != null && UserKernel.fileSystem.remove(name)) ? 0 : -1;
	}

	private static final int syscallHalt = 0, syscallExit = 1, syscallExec = 2,
			syscallJoin = 3, syscallCreate = 4, syscallOpen = 5,
			syscallRead = 6, syscallWrite = 7, syscallClose = 8,
			syscallUnlink = 9;

	/**
	 * Handle a syscall exception. Called by <tt>handleException()</tt>. The
	 * <i>syscall</i> argument identifies which syscall the user executed:
	 * 
	 * <table>
	 * <tr>
	 * <td>syscall#</td>
	 * <td>syscall prototype</td>
	 * </tr>
	 * <tr>
	 * <td>0</td>
	 * <td><tt>void halt();</tt></td>
	 * </tr>
	 * <tr>
	 * <td>1</td>
	 * <td><tt>void exit(int status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>2</td>
	 * <td><tt>int  exec(char *name, int argc, char **argv);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>3</td>
	 * <td><tt>int  join(int pid, int *status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>4</td>
	 * <td><tt>int  creat(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>5</td>
	 * <td><tt>int  open(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>6</td>
	 * <td><tt>int  read(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>7</td>
	 * <td><tt>int  write(int fd, char *buffer, int size);
	 * 								</tt></fileDescriptorTable>
	 * </tr>
	 * <tr>
	 * <td>8</td>
	 * <td><tt>int  close(int fd);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>9</td>
	 * <td><tt>int  unlink(char *name);</tt></td>
	 * </tr>
	 * </table>
	 * 
	 * @param syscall the syscall number.
	 * @param a0 the first syscall argument.
	 * @param a1 the second syscall argument.
	 * @param a2 the third syscall argument.
	 * @param a3 the fourth syscall argument.
	 * @return the value to be returned to the user.
	 */
	public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) {
		switch (syscall) {
			case syscallHalt:
				return handleHalt();
			case syscallExit:
				return handleExit(a0);
			case syscallExec:
				return handleExec(a0, a1, a2);
			case syscallJoin:
				return handleJoin(a0, a1);
			case syscallCreate:
				return handleCreate(a0);
			case syscallOpen:
				return handleOpen(a0);
			case syscallRead:
				return handleRead(a0, a1, a2);
			case syscallWrite:
				return handleWrite(a0, a1, a2);
			case syscallClose:
				return handleClose(a0);
			case syscallUnlink:
				return handleUnlink(a0);
			default:
				Lib.debug(dbgProcess, "Unknown syscall " + syscall);
		}
		return 0;
	}

	/**
	 * Handle a user exception. Called by <tt>UserKernel.exceptionHandler()</tt>
	 * . The <i>cause</i> argument identifies which exception occurred; see the
	 * <tt>Processor.exceptionZZZ</tt> constants.
	 * 
	 * @param cause the user exception that occurred.
	 */
	public void handleException(int cause) {
		Processor processor = Machine.processor();

		switch (cause) {
		case Processor.exceptionSyscall:
			int result = handleSyscall(processor.readRegister(Processor.regV0),
					processor.readRegister(Processor.regA0),
					processor.readRegister(Processor.regA1),
					processor.readRegister(Processor.regA2),
					processor.readRegister(Processor.regA3));
			processor.writeRegister(Processor.regV0, result);
			processor.advancePC();
			break;

		default:
			Lib.debug(dbgProcess, "Unexpected exception: "
					+ Processor.exceptionNames[cause]);
			handleExit(-1);
		}
	}

	/** The program being run by this process. */
	protected Coff coff;

	/** This process's page table. */
	protected TranslationEntry[] pageTable;

	protected OpenFile[] fdTable;

	/** The number of contiguous pages occupied by the program. */
	protected int numPages;

	/** The number of pages in the program's stack. */
	protected final int stackPages = 8;

	/** The thread that executes the user-level program. */
    protected UThread thread;
    
	private int initialPC, initialSP;

	private int argc, argv;

	private static final int pageSize = Processor.pageSize;

	private static final char dbgProcess = 'a';

	// essentially PID of the process
	private int processNumber;

	// TODO: protect with lock
	private static HashMap<Integer, UserProcess> childMap;

	private Lock childMapLock;

	private Integer status;

	private UserProcess parentProcess;
}
