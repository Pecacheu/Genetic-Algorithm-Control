import socket as Socket
import threading
from time import sleep
from sys import stdout

from keyReader import getch
import simConnect as sim

#Constants:
MSG_BADGE = "[GenCli] "
PCNAME = Socket.gethostname()
#HOSTNAME = "Rays-SP4"#"novalabs-sc-ctrl"
PORT = 50542
BCAST_PORT = 50541

#Vars:
connect = False
exitFlag = False
socket = None
sLock = threading.RLock()
HOSTIP = ""

pingTime = 0
pingSendTime = 0
pLock = threading.RLock()

#Useful Functions:
def dbg(msg):
    stdout.write(MSG_BADGE+msg+"\n")

def err(msg, e):
    stdout.write("Error: "+msg+"\n"+str(e)+"\n")

def closeSocket(nSleep=False):
    global connect
    global socket
    global pingTime
    global pingSendTime
    with sLock:
        if(connect):
            sim.stop(); connect = False
            socket.shutdown(Socket.SHUT_RDWR)
            socket.close(); socket = None
    with pLock:
        pingTime = 0; pingSendTime = 0
    if(not nSleep): sleep(2)

def close():
    global exitFlag
    exitFlag = True
    closeSocket(True)
    exit()

def serverWrite(cmd, data=""):
    with sLock:
        if(connect):
            try: socket.sendall(cmd+data+"\n")
            except Socket.error as e:
                err("Error while sending data to server!",e)
                dbg("Closing socket...")
                closeSocket()

#Read lines from server:
rlData = None
rlMsg = ""
def readLine():
    global rlData
    global rlMsg
    try:
        if(rlData == None or len(rlData) == 0):
            rlMsg = ""; rlData = socket.recv(256)
        else:
            #Remove previous parsed chars:
            if(len(rlMsg) > 0):
                prevLen = len(rlMsg)+1
                if(prevLen > 0):
                    rlData = rlData[prevLen:]
                rlMsg = ""
            rlData += socket.recv(256)
        #Parse data in order of arrival:
        for c in rlData:
            if(c == '\n'):
                return rlMsg.replace('\r', '') or False #Stop on newline.
            else:
                rlMsg += c #Add char to message
        return False
    except Socket.error as e:
        #err("Could not read data from server.",e)
        return False

#Main read thread:
def readThreadRun():
    global pingTime
    while(not exitFlag):
        with sLock:
            if(connect):
                line = readLine()
                if(line): #Respond to server messages:
                    with pLock: pingTime = 0
                    if(line[0] != 'P'): dbg("Read line from server: "+line)
                    if(line[0] == 'S'): #RUN SIM command
                        strArgs = line[1:].split(',')
                        sim.initRun(map(int,strArgs),simResult)
            elif(HOSTIP and openSocket()):
                serverWrite('N',PCNAME)
                dbg("Connected to server!")

#Wait for server connection:
def openSocket():
    global connect
    global socket
    sleep(0.5)
    try:
        if(not socket):
            socket = Socket.socket(Socket.AF_INET, Socket.SOCK_STREAM)
            socket.setsockopt(Socket.SOL_SOCKET, Socket.SO_REUSEADDR, 1)
            dbg("Waiting for server on port "+str(PORT)+"\n")
        socket.connect((HOSTIP, PORT)) #Socket.gethostbyname(HOSTNAME)
        connect = True; socket.setblocking(False)
        return True
    except Socket.error as e:
        #dbg("Trying to connect...")
        return False

#Exit on ESC key press:
def exitThread():
    dbg("Press ESCAPE at any time to exit.\n")
    while(not exitFlag):
        if(getch() == chr(27).encode()): #ESC Key
            dbg("Exiting...")
            close()

#Send keep-alive pings to server:
def pingThread():
    while(not exitFlag):
        bcastRecv()
        if(connect and not pingLoop()):
            dbg("Server connection timed out!")
            closeSocket()

def pingLoop():
    global pingTime
    global pingSendTime
    with sim.rLock: run = sim.running
    with pLock:
        pt = pingTime; pst = pingSendTime
    if(run and pingSendTime >= 5):
        serverWrite('U', str(sim.getStatus())) #Send status every 0.5s
        with pLock: pingSendTime = 0
    elif(pingSendTime >= 20):
        serverWrite('P') #Or send ping every 2s
        with pLock: pingSendTime = 0
    if(pingTime > 40): return False
    with pLock:
        pingTime += 1; pingSendTime += 1
    sleep(0.1); return True

#Send result on sim completion:
def simResult(res):
    dbg("Simulation complete! Result: "+str(res))
    serverWrite('R', str(res))

#Check for broadcast packets:
def bcastRecv():
    global HOSTIP
    try:
        (msg,host) = bcast.recvfrom(1)
        if(msg == 'G'):
            with sLock:
                if(host[0] != HOSTIP):
                    dbg("Server IP broadcast recieved! IP: "+str(host))
                    HOSTIP = host[0]
    except Socket.error as e: return

#Initial Setup:
dbg("GenCtrl Super-Computer Client\n")

dbg("Opening Broadcast Socket...")
bcast = Socket.socket(Socket.AF_INET, Socket.SOCK_DGRAM)
bcast.bind(('', BCAST_PORT)); bcast.setblocking(False)

threading.Thread(target=readThreadRun).start()
threading.Thread(target=pingThread).start()
threading.Thread(target=exitThread).start()
