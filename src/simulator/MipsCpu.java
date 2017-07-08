package simulator;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.IOException;
import java.nio.file.StandardOpenOption;
import java.util.LinkedList;
import java.util.Iterator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * @author Kevin Rapa
 */
public final class MipsCpu {
    
// <editor-fold defaultstate="collapsed" desc="ATTRIBUTES">
private final Instruction NOP_INST = new Nop_Inst(); // Do nothing instruction.   
private final static int IF = 0, ID = 1, MEM = 5;    // Used to insert bubbles.
private int programCounter = -4, clockTicks = 0;

private final LinkedList<Instruction> 
        PIPE = new LinkedList<>(); // The pipeline. Always has size of 6

private final HashMap<String, Integer> 
        SYMBOL_TABLE = new HashMap<>(); // Holds labels and addresses

private final int[] REGISTER_FILE = new int[32];

private final Memory MEMORY; // Encapsulates main memory anc caches

private final static HashSet<String> 
    R_TYPE_INST = new HashSet<>(), // All R-type instruction names.
    I_TYPE_INST = new HashSet<>(); // All I-type instruction names.

private final int[][]         // These hold data forwarded from future stages
    daForBuf = new int[1][3], // Use in MEM stage
    idForBuf = new int[4][3], // Use in ID stage
    exForBuf = new int[3][3]; // Use in EX stage

/*
    ___|_Reg_#_|_value_|_valid_| ID stage buffer
    EX1|_______|_______|_______|
    EX2|_______|_______|_______|
    EX3|_______|_______|_______|
    DAT|_______|_______|_______|

    ___|_Reg_#_|_value_|_valid_| EX stage buffer
    EX2|_______|_______|_______|
    EX3|_______|_______|_______|
    DAT|_______|_______|_______|

    ___|_Reg_#_|_value_|_valid_| DA stage buffer
    DAT|_______|_______|_______|
*/

static {
    // Adds all the instructions that this simulator supports.
    R_TYPE_INST.addAll(Arrays.asList(
            new String[] {"add", "sub", "and", "or", "mult"}
    ));
    I_TYPE_INST.addAll(Arrays.asList(new String[] {
        "addi", "subi", "andi", "ori", "multi", "lw", "sw", "li", "beq", "bne"
    }));
}
//</editor-fold>

//=========================================================================
public MipsCpu(String[][] ins, String[] orig, Integer[] dat) {
    Pattern colon = Pattern.compile(":");

    // Initialize pipeline to hold 6 NOP instructions.
    for (int i = 0; i < 6; i++) {
        this.PIPE.add(this.NOP_INST);
    }
    // Search for labels and add them to the symbol table.
    for (int i = 0; i < ins.length; i++) {
        String piece = ins[i][0];

        if (colon.matcher(piece).find()) {
            SYMBOL_TABLE.put(piece.substring(0, piece.length() - 1), i);
        }
    }

    this.MEMORY = new Memory(ins, orig, dat); // Create main memory and caches
}
//-------------------------------------------------------------------------
public void start(String outFile, boolean showPipe) {
    try {
        do {
            this.clockTicks++;
        } while (this.tick(outFile, showPipe));
        
        Files.write(Paths.get(outFile), MEMORY.getMemOut(), StandardOpenOption.APPEND);
    } 
    catch (ClassCastException e) {
        System.out.println("Couldn't cast word to Instruction. Forget to add HLT?");
    } 
    catch (NullPointerException e) {
        System.out.println("Null instruction encountered. Forget to add HLT?");
    }
    catch (IOException e) {
        System.out.println(e.getMessage());
    }
}
//-------------------------------------------------------------------------
private boolean tick(String out, boolean showPipe) 
        throws ClassCastException, NullPointerException, IOException 
{
    Instruction nextInst, last = PIPE.removeLast();
    Iterator<Instruction> iter = PIPE.descendingIterator();
    boolean writeBufEmpty;
    
    if (this.programCounter != -1) {
        programCounter += 4;
    }
    
    try {
        last.WB(); // Write back stage
        
        if (showPipe) {
            dumpPipe();
        }
        
        last.printOutput(out); // Prints output

        // Writes words to main memory if possible.
        writeBufEmpty = MEMORY.tryEmptyWriteBuf(); 
        
        iter.next().MEM(); // MEM stage
        iter.next().EX3(); // EX 3 stage
        iter.next().EX2(); // EX 2 stage
        iter.next().EX1(); // EX 1 stage
        iter.next().ID();  // ID stage
        
        if ((last instanceof Stop_Inst) && writeBufEmpty) {
            return false; // Ends the simulation.
        } 
        else if (programCounter < 0) {
            nextInst = new Stop_Inst(); // Helps end the simulation.
        } 
        else {
            nextInst = MEMORY.fetchI(programCounter);
            
            if (nextInst instanceof Hlt_Inst) {
                programCounter = -1; // Stops PC from changing. Ends program.
            }
        }
        
        PIPE.addFirst(nextInst);  // IF stage. Simply queues it to pipe.
        nextInst.updateOutput(0);
    } 
    catch (Stall e) {
        // Stalls. Decrements program counter and inserts a bubble.
        PIPE.add(e.STAGE, this.NOP_INST);
        
        if (this.programCounter != -1)
            programCounter -= 4;
    }
    catch (Flush e) {
        // Flushes the pipeline after a branch. Inserts a bubble.
        PIPE.addFirst(new Nop_Inst());
    }

    return true;
}
//-------------------------------------------------------------------------
public void dumpReg() {
    // Prints register file.
    for (int i = 0; i < REGISTER_FILE.length; i++) {
        System.out.println("R" + i + ": " + this.REGISTER_FILE[i]);
    }
}
//-------------------------------------------------------------------------
public void dumpPipe() {
    for (Instruction i : this.PIPE) {
        System.out.print(
                "[" + (i.NAME != null ? i.NAME.trim() : "NOP") + "]  =>  "
        );
    }

    System.out.println();
}

// INSTRUCTION AND DATA CLASSES ===========================================
private class Word {
    // Represents a word in memory. To be used as an instruction, it must be 
    // cast to one (Word meaning is contextual).
    public final int VALUE; // Meaningful if this isn't an instruction.
    
    public Word(int val) {
        this.VALUE = val;
    }

    @Override public String toString() {
        return String.valueOf(this.VALUE); 
    }
}
//-------------------------------------------------------------------------
abstract private class Instruction extends Word {
    public final String NAME; // Name of the instruction.
    protected int op1Data;   // Holds one operand. May have more.
    protected int[] output = new int[5];

    public Instruction(int val, String rep) { 
        super(val); 
        NAME = rep;
    }

    // The default behaviors in each pipeline stage.
    public void ID() throws Stall, Flush {
        updateOutput(1);
    }
    
    public void EX1() {}
    
    public void EX2() {}
    
    public void EX3() { 
        updateOutput(2); 
    }
    
    public void MEM() throws Stall { 
        updateOutput(3); 
    }
    
    public void WB()  { 
        updateOutput(4); 
    }

    abstract public Instruction copyOf(); // Memory only returns a copy of this.

    protected boolean loadWordHazard(int end, int ... regs) {
        // Returns true IF there is a load instruction in an EX stage.
        // param 'end' is the last row to search in the forward buffer.
        // Branch instructions should use '2', otherwise '1'.
        // If 2, checks in ex3. Always checks ex 1 and ex2.
        Iterator<Instruction> iter = MipsCpu.this.PIPE.iterator();
        iter.next();
        
        Instruction ex1 = iter.next();
        Instruction ex2 = iter.next();
        Instruction ex3 = iter.next();
        Instruction mem = iter.next();
        
        for (int reg : regs) {
            boolean result = 
                    ((ex1 instanceof Lw_Inst && ((Lw_Inst)ex1).RS == reg) ||
                     (ex2 instanceof Lw_Inst && ((Lw_Inst)ex2).RS == reg) ||
                     (ex3 instanceof Lw_Inst && ((Lw_Inst)ex3).RS == reg));
            
            if (end == 2) {
                result = result || (mem instanceof Lw_Inst && ((Lw_Inst)mem).RS == reg);
            }
            
            if (result) {
                return true;
            }
        }
        
        return false;
    }

    protected Integer forward(int destReg, int[][] buffer) {
        for (int[] row : buffer)
            // If row is valid and the destination register matches.
            if (row[2] == 1 && row[0] == destReg) {
                return row[1]; // Return the register contents.
            }
        
        return null; // Otherwise nothing is forwarded.
    }

    protected boolean multHazard(int dest, int distance) {
        // Returns true if there's a mult or multi instruction in
        // EX stage and it's writing to the matching register
        Instruction i = PIPE.get(1);

        boolean result = 
                (i instanceof Mult_Inst && ((Mult_Inst)i).RD == dest)
             || (i instanceof MultI_Inst && ((MultI_Inst)i).RT == dest);

        if (distance == 2) {
            // Checks EX2 stage as well. For branching instructions.
            Instruction i2 = PIPE.get(2);

            result = result || 
                    ((i2 instanceof Mult_Inst && ((Mult_Inst)i2).RD == dest)
                 || (i2 instanceof MultI_Inst && ((MultI_Inst)i2).RT == dest));
        }

        return result;
    }

    protected boolean addSubHazard(int ... regs) {
        // Returns true if there's an addi, subi, add, or sub instruction in
        // the EX1 stage and it's writing to the matching register. 
        Instruction i = PIPE.get(1);

        for (int r : regs) {
            if (
                    (i instanceof Add_Inst && ((Add_Inst)i).RD == r)
                 || (i instanceof AddI_Inst && ((AddI_Inst)i).RT == r)
                 || (i instanceof Sub_Inst && ((Sub_Inst)i).RD == r)
                 || (i instanceof SubI_Inst && ((SubI_Inst)i).RT == r)
               ) 
            {
                return true;
            }
        }
        
        return false;
    }
    
    protected void fillRow(int[][] t, int r, int des, int val, int vld) {
        t[r][0] = des; // The destination register.
        t[r][1] = val; // The value to be written to it.    
        t[r][2] = vld; // If this entry is valid.
    }

    public void updateOutput(int stage) {
        // Adds the clock cycle during which the instruction left a stage.
        this.output[stage] = clockTicks;
    }
    
    public void printOutput(String outFile) throws IOException {
        String out = NAME + ' ' + output[0] + ' ' + output[1] + ' ' 
                + output[2] + ' ' + output[3] + ' ' + output[4] + '\n';
        
        Files.write(Paths.get(outFile), out.getBytes(), StandardOpenOption.APPEND);
    }
}
//-------------------------------------------------------------------------


//<editor-fold defaultstate="collapsed" desc="R INSTRUCTIONS">
abstract private class R_Instruction extends Instruction {
    public final int RS, RT, RD;
    protected int op2Data, result;

    public R_Instruction(int rs, int rt, int rd, String name) {
        super(0, name);
        this.RS = rs; // Operand register 1
        this.RT = rt; // Operand register 2
        this.RD = rd; // Destination register
    }

    protected void checkForward(int[][] buf) {
        // Detects a data hazard and forwards the value.
        Integer f;

        if ((f = this.forward(RS, buf)) != null)
            op1Data = f; // Hazard detected. Forwards the value.

        if ((f = this.forward(RT, buf)) != null)
            op2Data = f; // Hazard detected. Forwards the value.
    }

    @Override public void ID() throws Stall {
        // Fetches the data in the registers.
        op1Data = REGISTER_FILE[RS];
        op2Data = REGISTER_FILE[RT];

        // Change data values if there's a hazard.
        checkForward(idForBuf);

        // Stalls if there's a multiply or load instruction.
        if (loadWordHazard(1, RS, RT) || multHazard(RS, 1) 
                || multHazard(RT, 1) || addSubHazard(RS, RT)) 
        {
            throw new Stall(this.NAME, MipsCpu.ID);
        }
        
        updateOutput(1);
    }

    @Override public void WB() {
        REGISTER_FILE[RD] = result; // Writes the data to the register.
        updateOutput(4);
    }

    @Override public void MEM() {
        // R-instructions do nothing here but update the forward buffer.
        fillRow(idForBuf, 3, RD, result, 1);
        fillRow(exForBuf, 2, RD, result, 1);
        fillRow(daForBuf, 0, RD, result, 1);
        updateOutput(3);
    }
}
//=========================================================================

//=========================================================================
private class Add_Inst extends R_Instruction {
    public Add_Inst(int rs, int rt, int rd, String op) { 
        super(rs, rt, rd, op); 
    }

    @Override public Instruction copyOf() {
        return new Add_Inst(RS, RT, RD, NAME);
    }

    @Override public void EX1() {
        checkForward(exForBuf);
        result = op1Data + op2Data;
    }

    @Override public void EX2() {
        fillRow(idForBuf, 1, RD, result, 1);
        fillRow(exForBuf, 0, RD, result, 1);
    }

    @Override public void EX3() {
        fillRow(idForBuf, 2, RD, result, 1);
        fillRow(exForBuf, 1, RD, result, 1);
        updateOutput(2);
    }
}
//-------------------------------------------------------------------------
private class Sub_Inst extends R_Instruction {
    public Sub_Inst(int rs, int rt, int rd, String op) { 
        super(rs, rt, rd, op);
    }

    @Override public Instruction copyOf() {
        return new Sub_Inst(RS, RT, RD, NAME);
    }

    @Override public void EX1() {
        checkForward(exForBuf);
        result = op1Data - op2Data;
    }

    @Override public void EX2() {
        fillRow(idForBuf, 1, RD, result, 1);
        fillRow(exForBuf, 0, RD, result, 1);
    }

    @Override public void EX3() {
        fillRow(idForBuf, 2, RD, result, 1);
        fillRow(exForBuf, 1, RD, result, 1);
        updateOutput(2);
    }
}
//-------------------------------------------------------------------------
private class Mult_Inst extends R_Instruction {
    public Mult_Inst(int rs, int rt, int rd, String op) { 
        super(rs, rt, rd, op); 
    }

    @Override public Instruction copyOf() {
        return new Mult_Inst(RS, RT, RD, NAME);
    }

    @Override public void EX1() {
        checkForward(exForBuf);
        result = op1Data * op2Data;
    }

    @Override public void EX3() {
        fillRow(idForBuf, 2, RD, result, 1);
        fillRow(exForBuf, 1, RD, result, 1);
        updateOutput(2);
    }
}
//-------------------------------------------------------------------------
private class And_Inst extends R_Instruction {
    public And_Inst(int rs, int rt, int rd, String op) { 
        super(rs, rt, rd, op);
    }

    @Override public Instruction copyOf() {
        return new And_Inst(RS, RT, RD, NAME);
    }

    @Override public void EX1() {
        checkForward(exForBuf);
        result = op1Data & op2Data;
        fillRow(idForBuf, 0, RD, result, 1);
    }

    @Override public void EX2() {
        fillRow(idForBuf, 1, RD, result, 1);
        fillRow(exForBuf, 0, RD, result, 1);
    }

    @Override public void EX3() {
        fillRow(idForBuf, 2, RD, result, 1);
        fillRow(exForBuf, 1, RD, result, 1);
        updateOutput(2);
    }
}
//-------------------------------------------------------------------------
private class Or_Inst extends R_Instruction {
    public Or_Inst(int rs, int rt, int rd, String op) { 
        super(rs, rt, rd, op); 
    }

    @Override public Instruction copyOf() {
        return new Or_Inst(RS, RT, RD, NAME);
    }

    @Override public void EX1() {
        checkForward(exForBuf);
        result = op1Data | op2Data;
        fillRow(idForBuf, 0, RD, result, 1);
    }

    @Override public void EX2() {
        fillRow(idForBuf, 1, RD, result, 1);
        fillRow(exForBuf, 0, RD, result, 1);
    }

    @Override public void EX3() {
        fillRow(idForBuf, 2, RD, result, 1);
        fillRow(exForBuf, 1, RD, result, 1);
        updateOutput(2);
    }
}
//=========================================================================
//</editor-fold>


//<editor-fold defaultstate="collapsed" desc="I INSTRUCTIONS">
abstract private class I_Instruction extends Instruction {
    public final int RS, RT, IMM;
    protected int result;

    public I_Instruction(int rs, int rt, int imm, String op) {
        super(0, op);
        
        if (imm > Math.pow(2, 15) - 1 || imm < -Math.pow(2, 15)) {
            // Immediate values must fit inside 16 bits.
            throw new UnsupportedOperationException(
                    "Immediate value cannot fit into 16 bits."
            );
        }
        
        this.RS = rs;
        this.RT = rt;
        this.IMM = imm;
    }

    protected void checkForward(int[][] buf) {
        Integer f;

        if ((f = this.forward(RS, buf)) != null)
            op1Data = f;
    }
}
//-------------------------------------------------------------------------
abstract private class Arith_I_Inst extends I_Instruction {
    // ADDI, SUBI, ANDI, ORI, MULTI
    public Arith_I_Inst(int rs, int rt, int imm, String op) {
        super(rs, rt, imm, op);
    }

    @Override public void ID() throws Stall {
        op1Data = REGISTER_FILE[RS];
        checkForward(idForBuf);

        if (loadWordHazard(1, RS) || multHazard(RS, 1) || addSubHazard(RS)) {
            throw new Stall(this.NAME, MipsCpu.ID);
        }
        
        updateOutput(1);
    }

    @Override public void MEM() {
        fillRow(idForBuf, 3, RT, result, 1);
        fillRow(exForBuf, 2, RT, result, 1);
        fillRow(daForBuf, 0, RT, result, 1);
        updateOutput(3);
    }

    @Override public void WB() {
        updateOutput(4);
        REGISTER_FILE[RT] = result;
    }
}
//-------------------------------------------------------------------------
abstract private class Branch_Inst extends I_Instruction {
    protected int op2Data;

    public Branch_Inst(int rs, int rt, int imm, String op) {
        super(rs, rt, imm, op);
    }

    abstract protected boolean comparison(int a, int b);

    @Override protected void checkForward(int[][] buf) {
        Integer f;
        
        if ((f = this.forward(RS, buf)) != null)
            op1Data = f;
        if ((f = this.forward(RT, buf)) != null)
            op2Data = f;
    }
    
    @Override protected boolean addSubHazard(int ... regs) {
        // Returns true if there's an addi, subi, add, or sub instruction in
        // the EX1 OR EX2 stage and it's writing to the matching register. 
        Instruction i = PIPE.get(2);
        
        if (super.addSubHazard(regs)) {
            return true;
        }
        else {
            for (int r : regs) {
                if ((i instanceof Add_Inst && ((Add_Inst)i).RD == r)
                 || (i instanceof AddI_Inst && ((AddI_Inst)i).RT == r)
                 || (i instanceof Sub_Inst && ((Sub_Inst)i).RD == r)
                 || (i instanceof SubI_Inst && ((SubI_Inst)i).RT == r)) 
                {
                    return true;
                }
            }

            return false;
        }
    }
    
    @Override public void ID() throws Stall, Flush {
        op1Data = REGISTER_FILE[RS];
        op2Data = REGISTER_FILE[RT];

        // Stalls if there's a multiply or load instruction.
        if (loadWordHazard(2, RS, RT) || multHazard(RS, 2) || 
                multHazard(RT, 2) || addSubHazard(RS, RT))
        {
            throw new Stall(this.NAME, MipsCpu.ID);
        }
        
        // Change data values if there's a hazard.
        checkForward(idForBuf);
        updateOutput(1);

        // Branch and flush if the BNE or BEQ instruction is true
        if (comparison(op1Data, op2Data)) {
            MipsCpu.this.programCounter = (this.IMM * 4) - 4;
            throw new Flush(this.getClass().getName());
        }
    }
    
    @Override public void printOutput(String outFile) throws IOException {
        String out = NAME + ' ' + output[0] + ' ' + output[1] + '\n';
        
        Files.write(Paths.get(outFile), out.getBytes(), StandardOpenOption.APPEND);
    }
}
//-------------------------------------------------------------------------
abstract private class Mem_Access_Inst extends I_Instruction {
    protected int op2Data;
    
    public Mem_Access_Inst(int rs, int rt, int imm, String op) {
        super(rs, rt, imm, op);
    }
    
    @Override public void ID() throws Stall {
        op1Data = REGISTER_FILE[RS];
        op2Data = REGISTER_FILE[RT];

        if (loadWordHazard(1, RT) || multHazard(RT, 1) || addSubHazard(RT)) {
            throw new Stall(this.NAME, MipsCpu.ID);
        }
        
        checkForward(idForBuf);
        updateOutput(1);
    }
    
    @Override public void EX1() {
        checkForward(exForBuf);
        this.result = op2Data + IMM;

        if ((result & 0b11) != 0) {
            throw new UnsupportedOperationException(
                "Effective address " + result + " not word aligned in " + NAME);
        }
    }
    
    @Override protected void checkForward(int[][] buf) {
        Integer f;

        if ((f = this.forward(RS, buf)) != null)
            op1Data = f;
        if ((f = this.forward(RT, buf)) != null)
            op2Data = f;
    }
}
//=========================================================================

//=========================================================================
private class AddI_Inst extends Arith_I_Inst {
    public AddI_Inst(int rt, int rs, int imm, String op) { 
        super(rs, rt, imm, op); 
    }

    @Override public Instruction copyOf() {
        return new AddI_Inst(RT, RS, IMM, NAME);
    }

    @Override public void EX1() {
        checkForward(exForBuf);
        result = op1Data + IMM;
    }

    @Override public void EX2() {
        fillRow(idForBuf, 1, RT, result, 1);
        fillRow(exForBuf, 0, RT, result, 1);
    }

    @Override public void EX3() {
        fillRow(idForBuf, 2, RT, result, 1);
        fillRow(exForBuf, 1, RT, result, 1);
        updateOutput(2);
    }
}
//-------------------------------------------------------------------------
private class SubI_Inst extends Arith_I_Inst {
    public SubI_Inst(int rt, int rs, int imm, String op) { 
        super(rs, rt, imm, op); 
    }

    @Override public Instruction copyOf() {
        return new SubI_Inst(RT, RS, IMM, NAME);
    }

    @Override public void EX1() {
        checkForward(exForBuf);
        result = op1Data - IMM;
    }

    @Override public void EX2() {
        fillRow(idForBuf, 1, RT, result, 1);
        fillRow(exForBuf, 0, RT, result, 1);
    }

    @Override public void EX3() {
        fillRow(idForBuf, 2, RT, result, 1);
        fillRow(exForBuf, 1, RT, result, 1);
        updateOutput(2);
    }
}
//-------------------------------------------------------------------------
private class MultI_Inst extends Arith_I_Inst {
    public MultI_Inst(int rt, int rs, int imm, String op) {
        super(rs, rt, imm, op); 
    }

    @Override public Instruction copyOf() {
        return new MultI_Inst(RT, RS, IMM, NAME);
    }

    @Override public void EX1() {
        checkForward(exForBuf);
        result = op1Data * IMM;
    }

    @Override public void EX3() {
        fillRow(idForBuf, 2, RT, result, 1);
        fillRow(exForBuf, 1, RT, result, 1);
        updateOutput(2);
    }
}
//-------------------------------------------------------------------------
private class AndI_Inst extends Arith_I_Inst {
    public AndI_Inst(int rt, int rs, int imm, String op) { 
        super(rs, rt, imm, op); 
    }

    @Override public Instruction copyOf() {
        return new AndI_Inst(RT, RS, IMM, NAME);
    }

    @Override public void EX1() {
        checkForward(exForBuf);
        result = op1Data & IMM;
        fillRow(idForBuf, 0, RT, result, 1);
    }

    @Override public void EX2() {
        fillRow(idForBuf, 1, RT, result, 1);
        fillRow(exForBuf, 0, RT, result, 1);
    }

    @Override public void EX3() {
        fillRow(idForBuf, 2, RT, result, 1);
        fillRow(exForBuf, 1, RT, result, 1);
        updateOutput(2);
    }
}
//-------------------------------------------------------------------------
private class OrI_Inst extends Arith_I_Inst {
    public OrI_Inst(int rt, int rs, int imm, String op) { 
        super(rs, rt, imm, op); 
    }

    @Override public Instruction copyOf() {
        return new OrI_Inst(RT, RS, IMM, NAME);
    }

    @Override public void EX1() {
        checkForward(exForBuf);
        result = op1Data | IMM;
        fillRow(idForBuf, 0, RT, result, 1);
    }

    @Override public void EX2() {
        fillRow(idForBuf, 1, RT, result, 1);
        fillRow(exForBuf, 0, RT, result, 1);
    }

    @Override public void EX3() {
        fillRow(idForBuf, 2, RT, result, 1);
        fillRow(exForBuf, 1, RT, result, 1);
        updateOutput(2);
    }
}
//-------------------------------------------------------------------------
private class Lw_Inst extends Mem_Access_Inst {
    private int load;
    
    public Lw_Inst(int rs, int rt, int imm, String op) { 
        super(rs, rt, imm, op); 
    }
    
    @Override public void MEM() throws Stall {
        checkForward(daForBuf);

        this.load = MEMORY.fetchData(result).VALUE;
        this.fillRow(idForBuf, 3, RS, load, 1);
        this.fillRow(exForBuf, 2, RS, load, 1);
        this.fillRow(daForBuf, 0, RS, load, 1);
        updateOutput(3);
    }
    
    @Override public void WB() {
        REGISTER_FILE[RS] = load;
        updateOutput(4);
    }
    
    @Override public Instruction copyOf() {
        return new Lw_Inst(RS, RT, IMM, NAME);
    }
}
//-------------------------------------------------------------------------
private class Sw_Inst extends Mem_Access_Inst {
    public Sw_Inst(int rs, int rt, int imm, String op) { 
        super(rs, rt, imm, op); 
    }
    
    @Override public void MEM() throws Stall {
        checkForward(daForBuf);
        MEMORY.writeWord(op1Data, result);
        updateOutput(3);
    }
    
    @Override public Instruction copyOf() {
        return new Sw_Inst(RS, RT, IMM, NAME);
    }
}
//-------------------------------------------------------------------------
private class Li_Inst extends I_Instruction {
    public Li_Inst(int rt, int rs, int imm, String op) { 
        super(rs, rt, imm, op); 
    }

    @Override public Instruction copyOf() {
        return new Li_Inst(RT, RS, IMM, NAME);
    }

    @Override public void EX1() {
        fillRow(idForBuf, 0, RT, IMM, 1);
    }

    @Override public void EX2() {
        fillRow(idForBuf, 1, RT, IMM, 1);
        fillRow(exForBuf, 0, RT, IMM, 1);
    }

    @Override public void EX3() {
        fillRow(idForBuf, 2, RT, IMM, 1);
        fillRow(exForBuf, 1, RT, IMM, 1);
        updateOutput(2);
    }

    @Override public void MEM() {
        fillRow(daForBuf, 0, RT, IMM, 1);
        fillRow(idForBuf, 3, RT, IMM, 1);
        fillRow(exForBuf, 2, RT, IMM, 1);
        updateOutput(3);
    }

    @Override public void WB() {
        REGISTER_FILE[this.RT] = this.IMM;
        updateOutput(4);
    }
}
//-------------------------------------------------------------------------
private class Bne_Inst extends Branch_Inst {
    public Bne_Inst(int rs, int rt, int imm, String op) { 
        super(rs, rt, imm, op); 
    }

    @Override public Instruction copyOf() {
        return new Bne_Inst(RS, RT, IMM, NAME);
    }

    @Override protected boolean comparison(int a, int b) {
        return a != b;
    }
}
//-------------------------------------------------------------------------
private class Beq_Inst extends Branch_Inst {
    public Beq_Inst(int rs, int rt, int imm, String op) { 
        super(rs, rt, imm, op); 
    }

    @Override public Instruction copyOf() {
        return new Beq_Inst(RS, RT, IMM, NAME);
    }

    @Override protected boolean comparison(int a, int b) {
        return a == b;
    }
}
//</editor-fold>


//<editor-fold defaultstate="collapsed" desc="J, NOP, AND HLT">
private class Nop_Inst extends Instruction {
    public Nop_Inst() { 
        super(0, null);
    }

    @Override public Instruction copyOf() {
        return this;
    }

    @Override public void EX1() {
        // Clears table rows as it follows the pipeline.
        this.fillRow(idForBuf, 0, 0, 0, 0);
    }

    @Override public void EX2() {
        this.fillRow(idForBuf, 1, 0, 0, 0);
        this.fillRow(exForBuf, 0, 0, 0, 0);
    }

    @Override public void EX3() {
        this.fillRow(idForBuf, 2, 0, 0, 0);
        this.fillRow(exForBuf, 1, 0, 0, 0);
    }

    @Override public void MEM() {
        this.fillRow(idForBuf, 3, 0, 0, 0);
        this.fillRow(exForBuf, 2, 0, 0, 0);
        this.fillRow(daForBuf, 0, 0, 0, 0);
    }
    
    @Override public void updateOutput(int i) {}
    
    @Override public void printOutput(String out) throws IOException {
        // Prints what the next instruction would have been after a flush.
        if (NAME != null)
            Files.write(Paths.get(out), NAME.getBytes(), StandardOpenOption.APPEND);
    };
}
//-------------------------------------------------------------------------
private class Hlt_Inst extends Instruction {
    public Hlt_Inst(String name) { 
        super(0, name);
    }

    @Override public Instruction copyOf() {
        return new Hlt_Inst(this.NAME);
    }
}
//-------------------------------------------------------------------------
private class Stop_Inst extends Hlt_Inst {
    public Stop_Inst() { 
        super("");
    }
    
    @Override public void printOutput(String out) {}
}
//-------------------------------------------------------------------------
private class J_Inst extends Instruction {
    protected final int ADDRESS;

    public J_Inst(int address, String op) {
        super(address, op);
        this.ADDRESS = address;
    }

    @Override public Instruction copyOf() {
        return new J_Inst(ADDRESS, NAME);
    }

    @Override public void ID() throws Flush {
        // Subtracts 4 to compensate for the add 4 at beginning of tick.
        MipsCpu.this.programCounter = ADDRESS - 4; 
        updateOutput(1);
        throw new Flush(this.getClass().getName());
    }
    
    @Override public void printOutput(String outFile) throws IOException {
        String out = NAME + ' ' + output[0] + ' ' + output[1] + '\n';
        
        Files.write(Paths.get(outFile), out.getBytes(), StandardOpenOption.APPEND);
    }
}
//</editor-fold>


//<editor-fold defaultstate="collapsed" desc="MEMORY">
private class Cache {
    protected final Word[][] CACHE;
    protected final int[] TAGS;
    protected final boolean[] VALID;
    protected final int BYTE_OFFSET = 2, WORD_BITS, BLOCK_BITS, BLOCK_SIZE;

    public Cache(int blocks, int words) {
        this.CACHE = new Word[blocks][words];
        this.TAGS = new int[blocks];
        this.VALID = new boolean[blocks];
        
        // Get # of bits to index a word in a block and bits to index a block
        this.WORD_BITS = (int)(Math.log10(words) / Math.log10(2.0));
        this.BLOCK_BITS = (int)(Math.log10(blocks) / Math.log10(2.0));
        this.BLOCK_SIZE = words;
    }
    
    public boolean hit(int address) {
        int[] bAndT = getBlockAndTag(address);
        int block = bAndT[0], paramTag = bAndT[1];
        return VALID[block] && (TAGS[block] == paramTag);
    }
    
    public Word fetch(int address) {
        // Returns a word from the cache. 
        int[] bAndT = getBlockAndTag(address);
        int i = getIndexIntoBlock(address);
        return CACHE[bAndT[0]][i];
    }
    
    public void write(Word word, int address) {
        int[] bAndT = getBlockAndTag(address);
        int i = this.getIndexIntoBlock(address);
        CACHE[bAndT[0]][i] = word;
    }
    
    public void populate(int address, Word[] memory) {
        int[] bAndT = this.getBlockAndTag(address);
        int block = bAndT[0], tag = bAndT[1];
        int start = address;
        
        // Descends down the memory to find the beginning of the block
        while (start - 4 >= 0 && getBlockAndTag(start - 4)[0] == block) {
            start -= 4;
        }
        
        // Ascends back up memory, putting everything into the cache block.
        for (int i = 0; i < BLOCK_SIZE; i++, start += 4) {
            this.CACHE[block][i] = memory[start];
        }
        
        this.VALID[block] = true;
        this.TAGS[block] = tag;
    }
    
    protected int[] getBlockAndTag(int address) {
        // Returns block index and tag of the address.
        int result[] = new int[2];
        address >>= (BYTE_OFFSET + WORD_BITS); // Shift out irrelevant bits
        result[0] = address % CACHE.length;    // Block index
        result[1] = address >> BLOCK_BITS;     // Tag
        return result;
    }
    
    protected int getIndexIntoBlock(int address) {
        address >>= BYTE_OFFSET;         // Shifts byte offset out
        int mask = (1 << WORD_BITS) - 1; // fills lowest [WORD_BITS] bits with 1's
        return address & mask;
    }
}

private class Memory {
    private static final int 
        RAM_SIZE = 512,     // Amount of bytes in memory.
        DATA_STRT = 0x100;  // The start of data words.
    
    // Stats to print at the end of the program.
    private int iCchRequests = 0, iCchHits = 0, dCchRequests = 0, dCchHits = 0;
    
    private final Stall 
        MEM_STALL = new Stall(this.getClass().getName(), MipsCpu.MEM),
        IF_STALL = new Stall(this.getClass().getName(), MipsCpu.IF);

    private final Word[] RAM = new Word[RAM_SIZE];
    
    private final Cache 
        I_Cache = new Cache(2, 8), // 2 blocks of 8 words
        D_Cache = new Cache(4, 4); // 4 blocks of 4 words
    
    // Used to keep track of what's accessing memory.
    private int iCacheTimer = 0, dCacheTimer = 0, bufferTimer = 0;
    private boolean ifBusy = false, memBusy = false, bufferBusy = false;
    
    // Hold words and the addresses to write them to main memory.
    private final LinkedList<Word> WRITE_BUF = new LinkedList<>();
    private final LinkedList<Integer> ADDRESS_BUF = new LinkedList<>();
    
    public Memory(String[][] ins, String[] orig, Integer[] dat) {
        Pattern colon = Pattern.compile(":");
        Pattern dig = Pattern.compile("-?(?:\\d+|[0-9a-f]+h)");
        Pattern parens = Pattern.compile("[()]");
    
        // Intitialize data in RAM
        for (int i = DATA_STRT, j = 0; j < dat.length; i += 4, j++)
            this.RAM[i] = new Word(dat[j]);
        
        // ADD ALL THE INSTRUCTIONS =========================================
        for (int i = 0, j = 0; i < ins.length; i++, j += 4) {
            StringBuilder b = new StringBuilder(orig[i]);
            String[] lex = ins[i];
            Instruction newInst;
            
            while (b.length() < 35) {
                b.append(' ');
            }
            String type = b.toString();
            
            int start = colon.matcher(lex[0]).find() ? 1 : 0;
            int numOps = lex.length - start - 1;
            String name = lex[start];

            if (MipsCpu.R_TYPE_INST.contains(name)) {
                // R type instruction.
                int rs = Integer.parseInt(lex[start + 2].substring(1));
                int rt = Integer.parseInt(lex[start + 3].substring(1));
                int rd = Integer.parseInt(lex[start + 1].substring(1));

                if (name.equals("add")) {
                    newInst = new Add_Inst(rs, rt, rd, type);
                } else if (name.equals("sub")) {
                    newInst = new Sub_Inst(rs, rt, rd, type);
                } else if (name.equals("and")) {
                    newInst = new And_Inst(rs, rt, rd, type);
                } else if (name.equals("or")) {
                    newInst = new Or_Inst(rs, rt, rd, type);
                } else {
                    newInst = new Mult_Inst(rs, rt, rd, type); 
                }
            }
            else if (MipsCpu.I_TYPE_INST.contains(name)) {
                // I type instruction.
                String op1 = lex[start + 1], op2 = lex[start + 2], op3;
                Integer rs = Integer.parseInt(op1.substring(1)), rt = 0, imm = 0;

                // Get rt and/or imm
                if (op2.charAt(0) == 'r') {
                    rt = Integer.parseInt(op2.substring(1));
                } 
                else if (parens.matcher(op2).find()) {
                    String[] offReg = parens.matcher(op2).replaceAll("").split("r");
                    String off = offReg[0], reg = offReg[1];

                    if (off.endsWith("h")) {
                        imm = Integer.parseInt(off.substring(0, off.length() - 1), 16);
                    } else {
                        imm = Integer.parseInt(off);
                    }

                    rt = Integer.parseInt(reg);
                } 
                else if (dig.matcher(op2).matches()) {
                    if (op2.endsWith("h")) {
                        imm = Integer.parseInt(op2.substring(0, op2.length() - 1), 16);
                    } else {
                        imm = Integer.parseInt(op2);
                    }
                }

                // Get third operand if there is one
                if (numOps == 3) {
                    op3 = lex[start + 3];

                    if (MipsCpu.this.SYMBOL_TABLE.containsKey(op3)) {
                        imm = MipsCpu.this.SYMBOL_TABLE.get(op3);
                    }
                    else if (dig.matcher(op3).matches()) {
                        if (op3.endsWith("h")) {
                            imm = Integer.parseInt(op3.substring(0, op3.length() - 1), 16);
                        } else {
                            imm = Integer.parseInt(op3);
                        }
                    }
                    else {
                        throw new UnsupportedOperationException("Label " + op3 + " was not found.");
                    }
                }

                if (name.equals("addi")) {
                    newInst = new AddI_Inst(rs, rt, imm, type);
                } else if (name.equals("subi")) {
                    newInst = new SubI_Inst(rs, rt, imm, type);
                } else if (name.equals("andi")) {
                    newInst = new AndI_Inst(rs, rt, imm, type);
                } else if (name.equals("ori")) {
                    newInst = new OrI_Inst(rs, rt, imm, type);
                } else if (name.equals("multi")) {
                    newInst = new MultI_Inst(rs, rt, imm, type);
                } else if (name.equals("lw")) {
                    newInst = new Lw_Inst(rs, rt, imm, type);
                } else if (name.equals("sw")) {
                    newInst = new Sw_Inst(rs, rt, imm, type);
                } else if (name.equals("li")) {
                    newInst = new Li_Inst(rs, rt, imm, type);
                } else if (name.equals("beq")) {
                    newInst = new Beq_Inst(rs, rt, imm, type);
                } else {
                    newInst = new Bne_Inst(rs, rt, imm, type);
                }
            } 
            else if (name.equals("j")) {
                Integer add = SYMBOL_TABLE.get(lex[start + 1]);

                if (SYMBOL_TABLE.containsKey(lex[start + 1])) {
                    newInst = new J_Inst(add * 4, type);
                } else {
                    throw new UnsupportedOperationException("J label " + add + " is invalid.");
                }
            } 
            else {
                newInst = new Hlt_Inst(type); 
            } 

            RAM[j] = newInst;
        }
    }
    //-------------------------------------------------------------------------
    public byte[] getMemOut() {
        return
            ('\n' +
            "Total number of access requests for instruction cache: " + iCchRequests + '\n' +
            "Number of instruction cache hits: " + iCchHits + '\n' + 
            '\n' +
            "Total number of access requests for data cache: " + dCchRequests + '\n' +
            "Number of data cache hits: " + dCchHits + '\n').getBytes();
    }
    //-------------------------------------------------------------------------
    public Instruction fetchI(int address) throws Stall {
        if (this.I_Cache.hit(address)) {
            // Found it in cache. Return the instruction,
            this.iCchRequests++;
            this.iCchHits++;
            return ((Instruction)I_Cache.fetch(address)).copyOf();
        } 
        else if (bufferBusy) {
            throw this.MEM_STALL;
        }
        else if (! ifBusy) {
            // Miss, and I_Cache hasn't started accessing.
            this.iCchRequests++;
            ifBusy = true;       // Begin fetch.
            iCacheTimer = 23;    // (8 words * 3 cycles each) - this cycle
            throw this.IF_STALL; // Prevent a new instruction from being added.
        }
        else if (iCacheTimer == 0) {
            // I_Cache has just finished working.
            ifBusy = false; // Stop work.
            this.I_Cache.populate(address, RAM); // Fill the block in cache.
            return ((Instruction)I_Cache.fetch(address)).copyOf();
        }
        else {
            // I_Cache is busy and hasn't finished.
            iCacheTimer--; // Work
            throw this.IF_STALL;
        }
    }
    //-------------------------------------------------------------------------
    public Word fetchData(int address) throws Stall {
        if (this.D_Cache.hit(address)) {
            // Hit, no need to access RAM.
            this.dCchHits++;
            this.dCchRequests++;
            return D_Cache.fetch(address);
        }
        else if (iCacheTimer != 0) {
            // Need to access RAM. Is the I_Cache accessing already?
            iCacheTimer--;          // Let I_Cache work.
            throw this.MEM_STALL;   // Insert bubble ahead of MEM stage.
        }
        else if (bufferBusy) {
            throw this.MEM_STALL;
        }
        else if (! memBusy) {
            // I_Cache is done or not busy.
            this.dCchRequests++;
            memBusy = true;   // Start accessing.
            dCacheTimer = 11; // (4 words * 3 cycles each) - 1 this cycle
            throw this.MEM_STALL;
        }
        else if (dCacheTimer == 0) {
            // D_Cache has just finished.
            memBusy = false;                     // Stop working.
            this.D_Cache.populate(address, RAM); // Fill the cache block.
            return D_Cache.fetch(address);       // Return word.
        }
        else {
            // D_Cache is busy. Let D_Cache work.
            dCacheTimer--;
            throw this.MEM_STALL;
        }
    }
    //-------------------------------------------------------------------------
    public void writeWord(int word, int address) throws Stall {
        if (this.D_Cache.hit(address)) {
            this.dCchHits++;
            this.dCchRequests++;
            Word w = new Word(word);
            this.D_Cache.write(w, address);
            this.WRITE_BUF.offer(w);
            this.ADDRESS_BUF.offer(address);
        }
        else if (iCacheTimer != 0) {
            iCacheTimer--;
            throw this.MEM_STALL;
        }
        else if (bufferBusy) {
            // Don't write if the write buffer is working
            throw this.MEM_STALL;
        }
        else if (! memBusy) {
            this.dCchRequests++;
            memBusy = true;
            dCacheTimer = 11;
            throw this.MEM_STALL;
        }
        else if (dCacheTimer == 0) {
            Word w = new Word(word);
            memBusy = false;
            this.D_Cache.populate(address, RAM);
            this.D_Cache.write(w, address);
            this.WRITE_BUF.offer(w);
            this.ADDRESS_BUF.offer(address);
        }
        else {
            dCacheTimer--;
            throw this.MEM_STALL;
        }
    }
    //-------------------------------------------------------------------------
    public boolean tryEmptyWriteBuf() {
        // Tries to write a word to main memory. Memory must not be in use.
        if (WRITE_BUF.isEmpty()) {
            return true; // Yes, buffer is empty.
        }
        else if (! bufferBusy && iCacheTimer == 0 && dCacheTimer == 0) {
            // Only start writing if everything else is done accessing.
            this.bufferTimer = 2;   // 2 cycles plus this one for 3 total.
            this.bufferBusy = true;
        }
        else if (bufferBusy && (--bufferTimer) == 0) {
            // Writes a word to memory
            this.bufferBusy = false;
            Word w = this.WRITE_BUF.poll();
            int address = this.ADDRESS_BUF.poll();
            RAM[address] = w;
        }
        
        return false; // Buffer is not empty.
    }
    //-------------------------------------------------------------------------
}
//</editor-fold>


//<editor-fold defaultstate="collapsed" desc="EXCEPTIONS">
private class Stall extends Exception {
    public final int STAGE;
    
    public Stall(String className, int stage) {
        super("Stall during " + stage + " of " + className);
        STAGE = stage;
    }
}
//-------------------------------------------------------------------------
private class Flush extends Exception {
    public Flush(String className) {
        super("Flushed during ID of " + className);
    }
}
//</editor-fold>

}