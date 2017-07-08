package simulator;

import java.util.*;
import java.util.regex.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * @author Kevin Rapa
 */
public class simulator {
    private final static Pattern 
        LABELP = Pattern.compile("\\w+:\\s+"),     // A label
        ALL_AFT_SPC = Pattern.compile(" .*"),      // All after first space
        FRST_WRD = Pattern.compile("\\w+ |\\w+$"); // Just the first word
    
    private final static LinkedList<String> 
        ORIGINALS = new LinkedList<>(); // Holds exactly what the user typed
    
    private final static HashMap<String, Pattern> 
        PATTERN_MAP = new HashMap<>();  // Contains valid syntax patterns 
    
    static {
        String imm =    "-?(?:\\d{1,5}|[0-9a-f]+h)"; // Signed immediate value
        String posImm = "(?:\\d{1,5}|[0-9a-f]+h)";   // Unsigned immediate
        String reg =    "r(?:[12]?[0-9]|3[01])";     // Register
        String delim =  ",\\s*";                     // Operand delimiter
        String lbl =    "\\w+";                      // A label symbol
        
        Pattern R_Pat =     Pattern.compile(reg + delim + reg + delim + reg);
        Pattern I_Pat =     Pattern.compile(reg + delim + reg + delim + imm);
        Pattern lwSw_Pat =  Pattern.compile(reg + delim + posImm + "\\(" + reg + "\\)");
        Pattern brnch_Pat = Pattern.compile(reg + delim + reg + delim + lbl);
        Pattern li_Pat =    Pattern.compile(reg + delim + imm);
        Pattern lbl_Pat =   Pattern.compile(lbl);
        Pattern nothing =   Pattern.compile("");

        // Maps each instruction to a pattern that matches its syntax.
        PATTERN_MAP.put("add", R_Pat);      PATTERN_MAP.put("sub", R_Pat);
        PATTERN_MAP.put("and", R_Pat);      PATTERN_MAP.put("or", R_Pat);
        PATTERN_MAP.put("mult", R_Pat);
        
        PATTERN_MAP.put("addi", I_Pat);     PATTERN_MAP.put("subi", I_Pat);
        PATTERN_MAP.put("andi", I_Pat);     PATTERN_MAP.put("ori", I_Pat);
        PATTERN_MAP.put("multi", I_Pat);
        
        PATTERN_MAP.put("li", li_Pat);      PATTERN_MAP.put("lw", lwSw_Pat);
        PATTERN_MAP.put("sw", lwSw_Pat);    PATTERN_MAP.put("j", lbl_Pat);
        PATTERN_MAP.put("bne", brnch_Pat);  PATTERN_MAP.put("beq", brnch_Pat);
        PATTERN_MAP.put("hlt", nothing);
    }
    
    public static void main(String[] args) {
        Scanner scan = new Scanner(System.in);
        
        System.out.println("Enter in: \"instruction file, data file, output file, [-p]\"");
        System.out.println("For example: inst.txt, data.txt, out.txt, -p");
        System.out.println("\"-p\" is optional to show pipeline scheduling instead of clock cycle stages.\n");
        System.out.println("This simulator supports instructions:\n"
                + "\tADD, SUB, AND, OR, MULT, ADDI, SUBI, ORI, ANDI, MULTI, J, BNE, BEQ, LW, SW, and LI.\n"
                + "End every program with the HLT instruction. Labels are supported as well.\n");
        System.out.println("Cycle time output is written to the output file and displays when each fetched\n"
                + "instruction leaves the IF, ID, EX, MEM, and WB stages.");
        System.out.print(">>> ");
        
        String answer = scan.nextLine();
        
        if (! answer.matches("\\w+?\\.txt,\\s*\\w+?\\.txt,\\s*\\w+?\\.txt(,\\s*(-p)?)?")) {
            System.err.println("Incorrect format.");
            return;
        }
        
        String[] params = answer.split(",\\s*");
        
        String insFile = params[0];
        String datFile = params[1];
        String outFile = params[2];
        
        try {
            String[][] commands = getInstructions(insFile);
            String[] originals = ORIGINALS.toArray(new String[ORIGINALS.size()]);
            Integer[] words = getWords(datFile);
            
            MipsCpu cpu = new MipsCpu(commands, originals, words);
            
            if (! Files.exists(Paths.get(outFile)))
                Files.createFile(Paths.get(outFile));
            
            
            
            cpu.start(outFile, params.length == 4 && params[3].equals("-p"));
        } catch (IOException | UnsupportedOperationException e) {
            System.err.println(e.getMessage());
        }
        
    }
    //-------------------------------------------------------------------------
    private static Integer[] getWords(String file) throws IOException {
        LinkedList<Integer> lines = new LinkedList<>();
        
        try(BufferedReader reader = 
                new BufferedReader(new FileReader(new File(file)))) 
        {
            String line;
            
            while ((line = reader.readLine()) != null) {
                if (! line.isEmpty()) {
                    lines.add(Integer.parseInt(line, Character.MIN_RADIX));
                }
            }
        }
        return lines.toArray(new Integer[lines.size()]);
    }
    //-------------------------------------------------------------------------
    private static String[][] getInstructions(String file) 
            throws IOException, UnsupportedOperationException 
    {
        LinkedList<String[]> lines = new LinkedList<>();
        
        try(BufferedReader reader = 
                new BufferedReader(new FileReader(new File(file)))) 
        {
            Pattern delimiter = Pattern.compile(",?\\s+");
            String line;
            
            while ((line = reader.readLine()) != null) {
                if (! line.isEmpty()) {
                    simulator.ORIGINALS.offer(line);
                    line = line.trim().toLowerCase();
                    verifySyntax(line);
                    String[] s = delimiter.split(line);
                    lines.add(s);
                }
            }
        }
        
        if (lines.size() > 256) {
            throw new UnsupportedOperationException("Data size must be 256 words or less.");
        }
        
        return lines.toArray(new String[lines.size()][]);
    }
    //-------------------------------------------------------------------------
    private static void verifySyntax(String inst) 
            throws UnsupportedOperationException 
    {
        /*
            Ensures that the instruction's syntax is correct. Does not
            check if labels in branching instructions correspond to an
            actual label.
        */
        
        String noLabel = LABELP.matcher(inst).replaceFirst("");
        String name = ALL_AFT_SPC.matcher(noLabel).replaceFirst("");
        
        if (PATTERN_MAP.keySet().contains(name)) {
            String noName = FRST_WRD.matcher(noLabel).replaceFirst("");
            Pattern p = PATTERN_MAP.get(name);
            
            if (! p.matcher(noName).matches()) {
                throw new UnsupportedOperationException(noName + 
                        " operands are incorrect for " + name);
            }
        } 
        else {
            throw new UnsupportedOperationException(name + 
                    " instruction not supported.");
        }
    }
    //-------------------------------------------------------------------------
}