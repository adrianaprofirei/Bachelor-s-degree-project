package accesscontrol;

import javacard.framework.*;
import javacardx.annotations.*;

/**
 * Applet class
 * 
 * @author <Aprofirei Adrian-Mihai>
 */

@StringPool(value = {
	    @StringDef(name = "Package", value = "accesscontrol"),
	    @StringDef(name = "AppletName", value = "accessControl")},
	    // Insert your strings here 
	name = "accessControlStrings")

public class accessControl extends Applet {

	final static byte accessControl_CLA = (byte) 0x80;
    final static byte VERIFY = (byte) 0x20;
    final static byte UPDATE_PIN = (byte) 0x30;
    final static byte CHECK_ACCESS = (byte) 0x40;
    final static byte EXIT_ZONE = (byte) 0x41;
    final static byte SET_ACCESS_ZONES = (byte) 0x50;
    final static byte SET_STUDENT_ZONE = (byte) 0x51;

    
    final static byte PIN_TRY_LIMIT = (byte) 0x03;
    final static byte MAX_PIN_SIZE = (byte) 0x08;
    final static short SW_VERIFICATION_FAILED = 0x6300;
    final static short SW_PIN_VERIFICATION_REQUIRED = 0x6301;
    final static short SW_ACCESS_DENIED = 0x6982;
    final static short SW_EXIT_DENIED = 0x6985;
    
    OwnerPIN pin;
    byte studentId;
    
    byte[] studentInZone = new byte[9]; // Track if student is in each zone
    byte[] accessZones = new byte[23];	//9 zones - course and lab classrooms take 8 bytes each
    //1 or 0 for the zones where student has(n't) got access
    //<course classroom access> - 1 byte
    //<lab classroom access> - 1 byte
    //<lecture classroom access> - 1 byte
    //<library access> - 1 byte
    //<bookstore access> - 1 byte
    //<canteen access> - 1 byte
    //<coffee shop access> - 1 byte
    //<confectionery access> - 1 byte
    //<dormitory access> - 1 byte
    //COURSE DATA <hours attended per day> <date HH> <date MM> <date ZZ> <date LL> <date AA> <hours attended per semester> - 7 bytes
    //LAB DATA <hours attended per day> <date HH> <date MM> <date ZZ> <date LL> <date AA> <hours attended per semester> - 7 bytes
    
    
    private accessControl(byte[] bArray, short bOffset, byte bLength) {
        pin = new OwnerPIN(PIN_TRY_LIMIT, MAX_PIN_SIZE);

        byte iLen = bArray[bOffset];
        bOffset = (short) (bOffset + iLen + 1);
        byte cLen = bArray[bOffset];
        bOffset = (short) (bOffset + cLen + 1);
        byte aLen = bArray[bOffset];

        pin.update(bArray, (short) (bOffset + 1), aLen);
        register();

    }
    
    public static void install(byte[] bArray, short bOffset, byte bLength) {
        new accessControl(bArray, bOffset, bLength);
    }

    @Override
    public boolean select() {
        return pin.getTriesRemaining() != 0;
    }

    @Override
    public void deselect() {
        pin.reset();
    }
    
    @Override
    public void process(APDU apdu) {
        byte[] buffer = apdu.getBuffer();

        if (apdu.isISOInterindustryCLA()) {
            if (buffer[ISO7816.OFFSET_INS] == (byte) (0xA4)) {
                return;
            }
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
        }
        
        if (buffer[ISO7816.OFFSET_CLA] != accessControl_CLA) {
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
        }

        switch (buffer[ISO7816.OFFSET_INS]) {
            case VERIFY:
                verify(apdu);
                return;
            case UPDATE_PIN:
            	updatePIN(apdu);
            	return;
            case CHECK_ACCESS:
                checkAccess(apdu);
                return;	
            case SET_ACCESS_ZONES:
                setAccessZones(apdu);
                return;
            case EXIT_ZONE:
                exitZone(apdu);
                return; 
            case SET_STUDENT_ZONE:
                setStudentZones(apdu);
                return;
            default:
                ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
        }

    }
    
    private void verify(APDU apdu) {
        byte[] buffer = apdu.getBuffer();
        byte byteRead = (byte) (apdu.setIncomingAndReceive());

        if (!pin.check(buffer, ISO7816.OFFSET_CDATA, byteRead)) {
            ISOException.throwIt(SW_VERIFICATION_FAILED);
        }
    }
    
    private void updatePIN(APDU apdu) {
        if (!pin.isValidated()) {
            ISOException.throwIt(SW_PIN_VERIFICATION_REQUIRED);
        }

        byte[] buffer = apdu.getBuffer();
        byte byteRead = (byte) (apdu.setIncomingAndReceive());

        if (byteRead != 10) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        byte[] newPin = new byte[5];
        Util.arrayCopy(buffer, (short) (ISO7816.OFFSET_CDATA + 5), newPin, (short) 0, (short) 5);

        if (!pin.check(buffer, ISO7816.OFFSET_CDATA, (byte) 5)) {
            ISOException.throwIt(SW_VERIFICATION_FAILED);
        }

        pin.update(newPin, (short) 0, (byte) 5);
    }
    
    private void checkAccess(APDU apdu) {
        byte[] buffer = apdu.getBuffer();

        if (!pin.isValidated()) {
            ISOException.throwIt(SW_PIN_VERIFICATION_REQUIRED);
        }

        byte zone = buffer[ISO7816.OFFSET_CDATA];
        if (accessZones[zone] == 1) {
        	studentInZone[zone] = 1;
            buffer[0] = (byte) 0x90; // SW1
            buffer[1] = (byte) 0x00; // SW2
        } else {
        	ISOException.throwIt(SW_ACCESS_DENIED);
        }
        apdu.setOutgoingAndSend((short) 0, (short) 2);
    }
    
    private void setAccessZones(APDU apdu) {
        if (!pin.isValidated()) {
            ISOException.throwIt(SW_PIN_VERIFICATION_REQUIRED);
        }

        byte[] buffer = apdu.getBuffer();
        byte byteRead = (byte) (apdu.setIncomingAndReceive());

        if (byteRead != 23) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        Util.arrayCopy(buffer, ISO7816.OFFSET_CDATA, accessZones, (short) 0, (short) 23);
    }
    
    private void setStudentZones(APDU apdu) {
        if (!pin.isValidated()) {
            ISOException.throwIt(SW_PIN_VERIFICATION_REQUIRED);
        }

        byte[] buffer = apdu.getBuffer();
        byte byteRead = (byte) (apdu.setIncomingAndReceive());

        if (byteRead != 9) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        Util.arrayCopy(buffer, ISO7816.OFFSET_CDATA, studentInZone, (short) 0, (short) 9);
    }
    
    private void exitZone(APDU apdu) {
        byte[] buffer = apdu.getBuffer();

        if (!pin.isValidated()) {
            ISOException.throwIt(SW_PIN_VERIFICATION_REQUIRED);
        }

        byte zone = buffer[ISO7816.OFFSET_CDATA];

        if (studentInZone[zone] != 1) {
        	ISOException.throwIt(SW_EXIT_DENIED);
        }

        studentInZone[zone] = 0;
        
        buffer[0] = (byte) 0x90; // SW1
        buffer[1] = (byte) 0x00; // SW2
        apdu.setOutgoingAndSend((short) 0, (short) 2);
    }

}
