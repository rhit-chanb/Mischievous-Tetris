import threading
def main():
    print("Hello world")
    threading.Thread(target=thread,args=("threading",)).start()

def thread(arg):
    print(arg)

if __name__ == "__main__":
    main()