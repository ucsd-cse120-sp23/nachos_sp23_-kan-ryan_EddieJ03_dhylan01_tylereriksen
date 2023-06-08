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
	@Override
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
		
		// big lock around everything
		UserKernel.freePPNLock.acquire();

		for (int s = 0; s < numCoffSections; s++) {
			CoffSection section = this.coff.getSection(s);

			Lib.debug(dbgProcess, "\tinitializing " + section.getName()
					+ " section (" + section.getLength() + " pages)");

			for (int i = 0; i < section.getLength(); i++) {
				int vpn = section.getFirstVPN() + i;
				virtPage = vpn;

				if(vpn == badPage) {
					// the ppn we will assign the faulting vpn to
					int ppn;

					// do we have enough free physical pages?
					if (UserKernel.freePPN.size() == 0) {
						// go ahead and sleep if everything is pinned
						if(VMKernel.numPinnedPages == Machine.processor().getNumPhysPages()) {
							VMKernel.condition.sleep();
						}

						UserProcess process = VMKernel.invertedPageTable[VMKernel.pageReplacementIndex].process;
						int vpnToEvict = VMKernel.invertedPageTable[VMKernel.pageReplacementIndex].vpn;
						boolean pin = VMKernel.invertedPageTable[VMKernel.pageReplacementIndex].pin;

						while(process.pageTable[vpnToEvict].used) {
							// if page is pinned increment VMKernel.pageReplacementIndex and continue
							if (pin) {
								VMKernel.pageReplacementIndex = (VMKernel.pageReplacementIndex + 1) % Machine.processor().getNumPhysPages();
								continue;
							}

							// set used to false
							process.pageTable[vpnToEvict].used = false;

							// increment VMKernel.pageReplacementIndex
							VMKernel.pageReplacementIndex = (VMKernel.pageReplacementIndex + 1) % Machine.processor().getNumPhysPages();

							// update process, vpn, pin
							process = VMKernel.invertedPageTable[VMKernel.pageReplacementIndex].process;
							vpnToEvict = VMKernel.invertedPageTable[VMKernel.pageReplacementIndex].vpn;
							pin = VMKernel.invertedPageTable[VMKernel.pageReplacementIndex].pin;
						}

						ppn = VMKernel.pageReplacementIndex;

						process.pagesForProcess.remove(Integer.valueOf(ppn));

						// if page is dirty write it out
						if (process.pageTable[vpnToEvict].dirty) {
							int swapPageNumber = VMKernel.openSwapArea.isEmpty() ? 
											 	 VMKernel.swapAreaCounter++ : 
											 	 VMKernel.openSwapArea.pollLast();

							VMKernel.swappingFile.write(swapPageNumber * Processor.pageSize, 
											   Machine.processor().getMemory(), 
											   Processor.makeAddress(process.pageTable[vpnToEvict].ppn, 0), 
											   Processor.pageSize);    

							// store the location in swap file in ppn
                        	process.pageTable[vpnToEvict].ppn = swapPageNumber;
						}

						// increment VMKernel.pageReplacementIndex
						VMKernel.pageReplacementIndex = (VMKernel.pageReplacementIndex + 1) % Machine.processor().getNumPhysPages();

						// invalidate evicted entry
						process.pageTable[vpnToEvict].valid = false;
					} else {
						ppn = UserKernel.acquirePPN();
					}

					pagesForProcess.add(ppn);

					if(pageTable[vpn].dirty) {
						// pin the page from inverted page table
						VMKernel.invertedPageTable[ppn].pin = true;

						// increment pinned count
						VMKernel.numPinnedPages += 1;

						VMKernel.swappingFile.read(pageTable[vpn].ppn * Processor.pageSize, 
										  Machine.processor().getMemory(), 
										  Processor.makeAddress(ppn, 0), 
										  Processor.pageSize);

                        VMKernel.openSwapArea.add(pageTable[vpn].ppn);

						pageTable[vpn].ppn = ppn;

                        pageTable[vpn].valid = pageTable[vpn].used = true;

						VMKernel.invertedPageTable[ppn].process = this;
                        VMKernel.invertedPageTable[ppn].vpn = vpn;
						VMKernel.invertedPageTable[ppn].pin = false;
						VMKernel.numPinnedPages -= 1;

						// release lock before returning
						UserKernel.freePPNLock.release();
						return;
					}

					pageTable[vpn].ppn = ppn;
					pageTable[vpn].readOnly = section.isReadOnly();
					pageTable[vpn].valid = true;
					pageTable[vpn].used = true;

					VMKernel.invertedPageTable[ppn].pin = true;
					VMKernel.numPinnedPages += 1;

					section.loadPage(i, ppn);

					VMKernel.invertedPageTable[ppn].process = this;
					VMKernel.invertedPageTable[ppn].vpn = vpn;
					VMKernel.invertedPageTable[ppn].pin = false;

					VMKernel.numPinnedPages -= 1;

					// release lock before returning since we are done
					UserKernel.freePPNLock.release();
					return;
				}
			}
		}

		// start the stack
		virtPage += 1;

		for(;virtPage < numPages; virtPage++) {
			if (virtPage == badPage) {
				// the ppn we will assign the faulting vpn to
				int ppn;

				// do we have enough free physical pages?
				if (UserKernel.freePPN.size() == 0) {
					UserProcess process = VMKernel.invertedPageTable[VMKernel.pageReplacementIndex].process;
					int vpn = VMKernel.invertedPageTable[VMKernel.pageReplacementIndex].vpn;
					boolean pin = VMKernel.invertedPageTable[VMKernel.pageReplacementIndex].pin;

					while(process.pageTable[vpn].used) {
						// set used to false
						process.pageTable[vpn].used = false;

						// increment VMKernel.pageReplacementIndex
						VMKernel.pageReplacementIndex = (VMKernel.pageReplacementIndex + 1) % Machine.processor().getNumPhysPages();

						// update process, vpn, pin
						process = VMKernel.invertedPageTable[VMKernel.pageReplacementIndex].process;
						vpn = VMKernel.invertedPageTable[VMKernel.pageReplacementIndex].vpn;
						pin = VMKernel.invertedPageTable[VMKernel.pageReplacementIndex].pin;
					}

					// the current index is the ppn we are giving to this user process
					ppn = VMKernel.pageReplacementIndex;

					process.pagesForProcess.remove(Integer.valueOf(ppn));

					// if page is dirty write it out
					if (process.pageTable[vpn].dirty) {
						int swapPageNumber = VMKernel.openSwapArea.isEmpty() ? 
											 VMKernel.swapAreaCounter++ : 
											 VMKernel.openSwapArea.pollLast();

						VMKernel.swappingFile.write(swapPageNumber * Processor.pageSize, 
											Machine.processor().getMemory(), 
											Processor.makeAddress(process.pageTable[vpn].ppn, 0), 
											Processor.pageSize);    

						// store the location in swap file in ppn
						process.pageTable[vpn].ppn = swapPageNumber;
					}

					// increment VMKernel.pageReplacementIndex
					VMKernel.pageReplacementIndex = (VMKernel.pageReplacementIndex + 1) % Machine.processor().getNumPhysPages();

					// invalidate evicted entry
					process.pageTable[vpn].valid = false;
				} else {
					ppn = UserKernel.acquirePPN();
				}

				pagesForProcess.add(ppn);

				if(pageTable[virtPage].dirty) {
					VMKernel.invertedPageTable[ppn].pin = true;
					VMKernel.numPinnedPages += 1;

					VMKernel.swappingFile.read(pageTable[virtPage].ppn * Processor.pageSize, 
										Machine.processor().getMemory(), 
										Processor.makeAddress(ppn, 0), 
										Processor.pageSize);

					VMKernel.openSwapArea.add(pageTable[virtPage].ppn);

					pageTable[virtPage].ppn = ppn;

					pageTable[virtPage].valid = pageTable[virtPage].used = true;

					VMKernel.invertedPageTable[ppn].process = this;
					VMKernel.invertedPageTable[ppn].vpn = virtPage;

					VMKernel.invertedPageTable[ppn].pin = false;
					VMKernel.numPinnedPages -= 1;
					
					// release lock before returning
					UserKernel.freePPNLock.release();
					return;
				}

				pageTable[virtPage].ppn = ppn;

				byte[] zeroedArray = new byte[Processor.pageSize];

				for(int i = 0; i < zeroedArray.length; i++) {
					zeroedArray[i] = 0;
				}

				System.arraycopy(zeroedArray, 0, Machine.processor().getMemory(), pageTable[virtPage].ppn * Processor.pageSize, Processor.pageSize);
				pageTable[virtPage].valid = true;
				pageTable[virtPage].used = true;

				VMKernel.invertedPageTable[ppn].process = this;
				VMKernel.invertedPageTable[ppn].vpn = virtPage;
				VMKernel.invertedPageTable[ppn].pin = false;

				// release lock before returning
				UserKernel.freePPNLock.release();
				return;
			}
		}

		// if we got here this means there was no page fault?
		UserKernel.freePPNLock.release();
	}

	// read bytes from file corresponding to fileDescriptor into buffer
	@Override
	protected int handleRead(int fileDescriptor, int buffer, int size) {
		int virtualAddressEnd = this.numPages * pageSize;

		if (fileDescriptor >= 0 && fileDescriptor < fdTable.length && fdTable[fileDescriptor] != null && size >= 0 && size < virtualAddressEnd
			&& buffer >= 0 && buffer < virtualAddressEnd && buffer+size < virtualAddressEnd) { 
			int firstVirtualPage = Processor.pageFromAddress(buffer);
			int lastVirtualPage = Processor.pageFromAddress(buffer + size);

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

		// lock here?
		VMKernel.lock.acquire();

		// copy everything we can until we exceed last virtual page allocated for this process
		// each time we are transferring at most pageSize
		while (currentVirtualPage < pageTable.length && currentVirtualPage <= endVirtualPage) {
			if (!pageTable[currentVirtualPage].valid) {
				System.out.println("PAGE FAULT");
				handlePagefault(Processor.makeAddress(currentVirtualPage, 0));

				if (!pageTable[currentVirtualPage].valid) {
					break;
				}
			}

			// if we are not reading and this is a readOnly page
			if (!read && pageTable[currentVirtualPage].readOnly) {
				break;
			}

			// pin the page from inverted page table
			VMKernel.invertedPageTable[pageTable[currentVirtualPage].ppn].pin = true;

			// increment pinned count
			VMKernel.numPinnedPages += 1;
			
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

			// set pin to false
			VMKernel.invertedPageTable[pageTable[currentVirtualPage].ppn].pin = false;

			// decrement pin page count
			VMKernel.numPinnedPages -= 1;

			// wake up a process possibly waiting
			VMKernel.condition.wake();

			pageTable[currentVirtualPage].used = true;

			if (!read) {
				pageTable[currentVirtualPage].dirty = true;
			}

			currentVirtualPage++;
		}		

		// transfer done, release lock
		VMKernel.lock.release();

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
