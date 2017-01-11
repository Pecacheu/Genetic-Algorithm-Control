# Genetic Algorithm Control System

### How To Use:

Server app under `GeneticCtrl/target/GeneticControl.jar` (Java 8+ Recommended)  
Edit the generated `config.cnf` and specify max/mins of desired parameters.

Progress of each generation will be displayed real-time in a window.  
Type 'run' to start running simulations. Type 'exit' or close window to exit.  
Results are written to `output.cnf`.

Client script under `genClient/GeneticClient.py`  
Simulator launch code goes in `genClient/simConnect.py`

Put the client app on several computers and connect over local network, or just run the server and client on the same computer and the server will make some "virtual" clients.

You can assign this command to a hotkey in Ubuntu Keyboard Settings to launch the client quickly:  
`gnome-terminal -t 'Genetic Client' --maximize -e 'python Desktop/genClient/GeneticClient.py'`