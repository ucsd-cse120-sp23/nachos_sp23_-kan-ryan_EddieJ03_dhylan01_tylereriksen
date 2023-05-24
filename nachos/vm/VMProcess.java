package nachos.vm;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;

/**
 * A <tt>UserProcess</tt> that supports demand-paging.
 */
public class VMProcess extends UserProcess {
	/**
	 * Allocate a new process.
	 */
	public VMProcess() {
		super();
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
		super.saveState();
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		super.restoreState();
	}

	/**
	 * Initializes page tables for this process so that the executable can be
	 * demand-paged.
	 * 
	 * @return <tt>true</tt> if successful.
	 */
	protected boolean loadSections() {
		this.pageTable = new TranslationEntry[numPages];

		for (int i = 0; i < numPages; i++) {
			pageTable[i] = new TranslationEntry(i, -1, false, false, false, false);
		}

		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
		super.unloadSections();
	}

	public void handlePagefault(int virtualAddress) {
		int badPage = Processor.pageFromAddress(virtualAddress);
		int numCoffSections = this.coff.getNumSections();
		int virtPage = 0;

		for (int s = 0; s < numCoffSections; s++) {
			CoffSection section = this.coff.getSection(s);

			Lib.debug(dbgProcess, "\tinitializing " + section.getName()
					+ " section (" + section.getLength() + " pages)");

			for (int i = 0; i < section.getLength(); i++) {
				int vpn = section.getFirstVPN() + i;
				virtPage += 1;

				if(vpn == badPage) {
					UserKernel.freePPNLock.acquire();

					// do we have enough free physical pages?
					if (UserKernel.freePPN.size() == 0) {
						coff.close();
						Lib.debug(dbgProcess, "\tinsufficient physical memory");
						UserKernel.freePPNLock.release();
						
						// need to swap
					}
				
					int ppn = UserKernel.acquirePPN();

					UserKernel.freePPNLock.release();

					pageTable[vpn].ppn = ppn;
					pageTable[vpn].readOnly = section.isReadOnly();
					pageTable[vpn].valid = true;

					section.loadPage(i, pageTable[vpn].ppn);
					return;
				}
			}
		}

		// now zeroing out stack page
		virtPage += 1;

		for(;virtPage < numPages; virtPage++) {
			if (virtPage == badPage) {
				UserKernel.freePPNLock.acquire();

				// do we have enough free physical pages?
				if (UserKernel.freePPN.size() == 0) {
					coff.close();
					Lib.debug(dbgProcess, "\tinsufficient physical memory");
					UserKernel.freePPNLock.release();
					
					// need to swap
				}
			
				int ppn = UserKernel.acquirePPN();

				UserKernel.freePPNLock.release();

				pageTable[virtPage].ppn = ppn;

				byte[] zeroedArray = new byte[Processor.pageSize];

				for(int i = 0; i < zeroedArray.length; i++){
					zeroedArray[i] = 0;
				}

				System.arraycopy(zeroedArray, 0, Machine.processor().getMemory(), pageTable[virtPage].ppn * Processor.pageSize, Processor.pageSize);
				pageTable[virtPage].valid = true;
				return;
			}
		}
	}

	// read bytes from file corresponding to fileDescriptor into buffer
	@Override
	protected int handleRead(int fileDescriptor, int buffer, int size) {
		int virtualAddressEnd = this.numPages * pageSize;

		if (fileDescriptor >= 0 && fileDescriptor < fdTable.length && fdTable[fileDescriptor] != null && size >= 0 && size < virtualAddressEnd
			&& buffer >= 0 && buffer < virtualAddressEnd) { 
			int firstVirtualPage = Processor.pageFromAddress(buffer);
			int lastVirtualPage = Processor.pageFromAddress(buffer + size);

			while (firstVirtualPage <= lastVirtualPage) {
				if (lastVirtualPage >= pageTable.length) {
					return -1;
				}

				// if not valid handle page fault
				if (!pageTable[firstVirtualPage].valid) {
					handlePagefault(Processor.makeAddress(firstVirtualPage, 0)); 
				}

				if (pageTable[firstVirtualPage].readOnly) {
					return -1;
				}

				firstVirtualPage++;
			}

			byte[] local = new byte[size];

			int result = fdTable[fileDescriptor].read(local, 0, size);

			if (result != -1) { 
				// write it to virtual mem
				// what if we couldn't write all of result?
				int written = writeVirtualMemory(buffer, local, 0, result);
				if(written != result) {
					return -1;
				}
				return written;
			}
		}

		return -1;	
	}

	// read amount of size bytes from buffer to fileDescriptor
	@Override
	protected int handleWrite(int fileDescriptor, int buffer, int size) {
		int virtualAddressEnd = this.numPages * pageSize;

		if (fileDescriptor >= 0 && fileDescriptor < fdTable.length && fdTable[fileDescriptor] != null && size >= 0 
			&& buffer >= 0 && buffer < virtualAddressEnd && buffer+size < virtualAddressEnd) { 
			int firstVirtualPage = Processor.pageFromAddress(buffer);
			int lastVirtualPage = Processor.pageFromAddress(buffer + size); 

			while (firstVirtualPage <= lastVirtualPage) {
				// if not valid handle page fault
				if (!pageTable[firstVirtualPage].valid) {
					handlePagefault(Processor.makeAddress(firstVirtualPage, 0)); 
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

	/**
	 * Overriden helper method to actually do transfer needed for read/writeVirtualMemory
	 * This took forever to figure out ( T _ T )
	 * 
	 * @param virtualAddress the first byte of virtual memory to write.
	 * @param data the array containing the data to transfer.
	 * @param offset the first byte to transfer from the array.
	 * @param length the number of bytes to transfer from the array to virtual
	 * memory.
	 * @param read whether this is read or not
	 * @return the number of bytes successfully transferred.
	 */
	@Override
	protected int transfer(int virtualAddress, byte[] data, int offset, int length, boolean read) {
		byte[] memory = Machine.processor().getMemory();

		int transferAmount = 0, virtualAddressEnd = this.numPages * pageSize;

		// not within bounds of process' virtual address space
		if (virtualAddress < 0 || virtualAddress >= virtualAddressEnd)
			return transferAmount;

		int currentVirtualPage = Machine.processor().pageFromAddress(virtualAddress);
		int endVirtualPage = Machine.processor().pageFromAddress(virtualAddress+length);
		int virtualLengthEnd = virtualAddress+length;

		// copy everything we can until we exceed last virtual page allocated for this process
		// each time we are transferring at most pageSize
		while (currentVirtualPage < pageTable.length && currentVirtualPage <= endVirtualPage) {
			if (!pageTable[currentVirtualPage].valid) {
				System.out.println("PAGE FAULT");
				handlePagefault(Processor.makeAddress(currentVirtualPage, 0)); 
			}

			// if we are not reading and this is a readOnly page
			if (!read && pageTable[currentVirtualPage].readOnly) {
				break;
			}
			
			int currentPageVAStart = currentVirtualPage * pageSize;
			int currentPageVAEnd = currentPageVAStart + pageSize - 1;

			int pageCase = pageCase(virtualAddress, virtualLengthEnd, currentPageVAStart, currentPageVAEnd);

			int addrOffset, physicalAddress, dataStart, difference;

			switch (pageCase) {
				case 0:
					addrOffset = Machine.processor().offsetFromAddress(virtualAddress);
					physicalAddress = pageSize * pageTable[currentVirtualPage].ppn + addrOffset;
					dataStart = offset+transferAmount;
					difference = pageSize - addrOffset;

					if (read) {
						System.arraycopy(memory, physicalAddress, data, dataStart, difference);
					} else {
						System.arraycopy(data, dataStart, memory, physicalAddress, difference);
					}

					transferAmount = transferAmount + difference;
					break;
				case 1:
					physicalAddress = pageSize * pageTable[currentVirtualPage].ppn;
					dataStart = offset+transferAmount;

					if (read) {
						System.arraycopy(memory, physicalAddress, data, dataStart, pageSize);
					} else {
						System.arraycopy(data, dataStart, memory, physicalAddress, pageSize);
					}

					transferAmount = transferAmount + pageSize;
					break;
				case 2:
					physicalAddress = pageSize * pageTable[currentVirtualPage].ppn;
					dataStart = offset+transferAmount;
					difference = virtualLengthEnd - currentPageVAStart;

					if (read) {
						System.arraycopy(memory, physicalAddress, data, dataStart, difference);
					} else {
						System.arraycopy(data, dataStart, memory, physicalAddress, difference);
					}

					transferAmount = transferAmount + difference;
					break;
				case 3:
					addrOffset = Machine.processor().offsetFromAddress(virtualAddress);
					physicalAddress = pageSize * pageTable[currentVirtualPage].ppn + addrOffset;
					dataStart = offset+transferAmount;

					if (read) {
						System.arraycopy(memory, physicalAddress, data, dataStart, length);
					} else {
						System.arraycopy(data, dataStart, memory, physicalAddress, length);
					}

					transferAmount = transferAmount + length;
			}

			pageTable[currentVirtualPage].used = true;

			if (!read) {
				pageTable[currentVirtualPage].dirty = true;
			}

			currentVirtualPage++;
		}		

		return transferAmount;
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
		case Processor.exceptionPageFault:
			handlePagefault(processor.readRegister(Processor.regBadVAddr)); 
			break;
		default:
			super.handleException(cause);
			break;
		}
	}

	private static final int pageSize = Processor.pageSize;

	private static final char dbgProcess = 'a';

	private static final char dbgVM = 'v';
}
