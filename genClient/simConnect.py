import threading
from time import sleep

#This is a basic example test simulator for GeneticClient

running = False
rLock = threading.RLock()
status = 0
statLock = threading.RLock()

def getStatus():
    with statLock: return status

def setStatus(stat):
    global status
    with statLock: status = int(stat)

def initRun(args, callback):
    stop(); threading.Thread(target=simThread,args=(args,callback)).start()

def simThread(args, callback):
    global running
    print "RUNNING SIM, ARGS: "+str(args)
	with rLock: running = True
    setStatus(0); res = run(args)
    callback(res)

#Return lower numbers for more success. (EX: faster speed)
#Return -1 if failed or stopped.
def run(args):
    #>> RUN & CONNECT TO SIMULATION HERE <<
    num = 0
    while(num < 60):
        with rLock:
            if(not running): return -1
            setStatus(num/60.0*100.0); num += 1
        sleep(0.1)
	with rLock: running = False #>> DONT FORGET TO SET RUNNING TO FALSE WHEN SIMULATION IS FINISHED! <<
    return abs(4-args[0])+abs(2-args[1])+abs(42-args[2])

#Abruptly stop any running simulation.
def stop():
    global running
    with rLock:
        if(running):
            running = False
            #>> SIMULATION TERMINATION CODE HERE <<
            print "SIM TERMINATED"
